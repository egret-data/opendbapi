package io.opendbapi.sql.inspect;

import java.util.ArrayList;
import java.util.List;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

/**
 * Static checks on rendered SQL before an endpoint is published (spec §7.5). Errors block
 * publishing; warnings are shown to the author. SQL the parser does not understand (vendor
 * syntax) is a warning, not an error, so dialect features remain usable.
 */
public final class SqlInspector {

    public enum Severity { ERROR, WARNING }

    public record SqlFinding(Severity severity, String code, String message) {
    }

    /**
     * @param readOnlyEndpoint the endpoint answers GET, so it must not change data
     * @param readOnlyDatasource the datasource is declared read-only
     */
    public record Context(boolean readOnlyEndpoint, boolean readOnlyDatasource) {
    }

    private SqlInspector() {
    }

    public static boolean hasErrors(List<SqlFinding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    /** Whether the SQL changes data: anything but a plain SELECT (SELECT … INTO counts as a write). */
    public static boolean isWrite(String sql) {
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(sql);
            for (Statement statement : parsed) {
                if (!(statement instanceof Select select)) {
                    return true;
                }
                SelectFacts facts = new SelectFacts();
                facts.visit(select, true);
                if (facts.into) {
                    return true;
                }
            }
            return false;
        } catch (JSQLParserException | RuntimeException e) {
            return writeKeyword(sql) != null;
        }
    }

    public static List<SqlFinding> inspect(String sql, Context context) {
        List<SqlFinding> findings = new ArrayList<>();
        List<Statement> statements;
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(sql);
            statements = new ArrayList<>(parsed);
        } catch (JSQLParserException | RuntimeException e) {
            findings.add(new SqlFinding(Severity.WARNING, "unparsed",
                    "The SQL could not be analysed (vendor-specific syntax?). Checks for missing WHERE and "
                            + "multiple statements were skipped; review it yourself before publishing."));
            // Not understood is not a pass: a write keyword anywhere still blocks read-only contexts,
            // e.g. PostgreSQL's data-modifying CTE "WITH d AS (DELETE …) SELECT …".
            String keyword = writeKeyword(sql);
            if (keyword != null && context.readOnlyEndpoint()) {
                findings.add(new SqlFinding(Severity.ERROR, "write_in_get",
                        "A GET endpoint must not change data, and this SQL contains %s.".formatted(keyword)));
            }
            if (keyword != null && context.readOnlyDatasource()) {
                findings.add(new SqlFinding(Severity.ERROR, "write_on_read_only",
                        "The datasource is read-only, and this SQL contains %s.".formatted(keyword)));
            }
            return findings;
        }
        if (statements.size() > 1) {
            findings.add(new SqlFinding(Severity.ERROR, "multiple_statements",
                    "Only one SQL statement is allowed per query; found %d.".formatted(statements.size())));
        }
        for (Statement statement : statements) {
            inspect(statement, context, findings);
        }
        return findings;
    }

    private static void inspect(Statement statement, Context context, List<SqlFinding> findings) {
        boolean write;
        if (statement instanceof Select select) {
            SelectFacts facts = new SelectFacts();
            facts.visit(select, true);
            write = facts.into;
            if (facts.star) {
                findings.add(new SqlFinding(Severity.WARNING, "select_star",
                        "SELECT * makes the response change whenever a column is added; list the columns instead."));
            }
            if (facts.forUpdate) {
                findings.add(new SqlFinding(Severity.WARNING, "locks_rows",
                        "SELECT … FOR UPDATE/SHARE locks rows until the request ends."));
            }
        } else {
            write = true;
        }
        if (write && context.readOnlyEndpoint()) {
            findings.add(new SqlFinding(Severity.ERROR, "write_in_get",
                    "A GET endpoint must not change data; use POST, PUT or DELETE for this statement."));
        }
        if (write && context.readOnlyDatasource()) {
            findings.add(new SqlFinding(Severity.ERROR, "write_on_read_only",
                    "The datasource is read-only; this statement changes data."));
        }
        if (statement instanceof Update u && u.getWhere() == null) {
            findings.add(new SqlFinding(Severity.ERROR, "missing_where", "UPDATE without WHERE changes every row."));
        }
        if (statement instanceof Delete d && d.getWhere() == null) {
            findings.add(new SqlFinding(Severity.ERROR, "missing_where", "DELETE without WHERE removes every row."));
        }
        if (isDdl(statement)) {
            findings.add(new SqlFinding(Severity.WARNING, "ddl",
                    "This statement changes the schema (%s).".formatted(statement.getClass().getSimpleName())));
        }
    }

    private static final java.util.regex.Pattern WRITE_KEYWORD = java.util.regex.Pattern.compile(
            "\\b(insert|update|delete|merge|upsert|replace|drop|alter|truncate|create|grant|revoke|call|exec|execute|copy|lock)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** First data-changing keyword outside string literals, quoted identifiers and comments. */
    static String writeKeyword(String sql) {
        String code = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("'(?:[^']|'')*'", "''")
                .replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"");
        java.util.regex.Matcher m = WRITE_KEYWORD.matcher(code);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    private static boolean isDdl(Statement statement) {
        String pkg = statement.getClass().getPackageName();
        return pkg.contains(".create.") || pkg.endsWith(".alter") || pkg.contains(".alter.")
                || pkg.endsWith(".drop") || pkg.endsWith(".truncate") || pkg.endsWith(".comment");
    }

    /** What a SELECT does, looking through set operations, parentheses and CTEs. */
    private static final class SelectFacts {
        boolean star;
        boolean into;
        boolean forUpdate;

        void visit(Select select, boolean topLevel) {
            // JSqlParser 5.0 only parses SELECT CTEs; data-modifying ones fail to parse and are
            // caught by the keyword fallback in inspect(String, Context).
            if (select.getWithItemsList() != null) {
                for (WithItem with : select.getWithItemsList()) {
                    if (with.getSelect() != null) {
                        visit(with.getSelect(), false);
                    }
                }
            }
            if (select instanceof PlainSelect plain) {
                if (topLevel && plain.getSelectItems() != null) {
                    for (SelectItem<?> item : plain.getSelectItems()) {
                        if (item.getExpression() instanceof AllColumns) {
                            star = true;
                        }
                    }
                }
                if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
                    into = true;
                }
                if (plain.getForMode() != null) {
                    forUpdate = true;
                }
            } else if (select instanceof SetOperationList set) {
                for (Select part : set.getSelects()) {
                    visit(part, topLevel);
                }
            } else if (select instanceof ParenthesedSelect parenthesed && parenthesed.getSelect() != null) {
                visit(parenthesed.getSelect(), topLevel);
            }
        }
    }
}
