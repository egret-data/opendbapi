package io.opendbapi.sql.policy;

import io.opendbapi.sql.template.BoundSql;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

/**
 * Restricts a rendered query to the rows whose result column equals the caller's value
 * (spec §12.2). The condition goes into the query's own WHERE clause, on the expression that
 * produces the column, so ORDER BY, grouping and window functions see only the caller's rows.
 * Only when the column comes from {@code *} over a derived table or join, and the query has
 * no top-level ORDER BY, is the query wrapped as {@code SELECT * FROM (...) WHERE col = ?}.
 * Anything else fails closed with {@link UnsupportedException}.
 */
public final class RowFilters {

    /** The filter cannot be applied safely to this query. The message is for authors. */
    public static final class UnsupportedException extends RuntimeException {
        public UnsupportedException(String message) {
            super(message);
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    private static final String MARKER = "opendbapi_rf_marker";

    private RowFilters() {
    }

    public static boolean validColumn(String column) {
        return column != null && IDENTIFIER.matcher(column).matches();
    }

    /**
     * @param values the caller's allowed values: one means {@code = ?}, several {@code IN (...)},
     *               none matches no row
     * @throws IllegalArgumentException for a column that is not a plain identifier
     * @throws UnsupportedException when the query's shape does not allow a safe filter
     */
    public static BoundSql apply(BoundSql query, String column, List<?> values) {
        if (!validColumn(column)) {
            throw new IllegalArgumentException("Not a plain column name: " + column);
        }
        String sql = stripTerminator(query.sql());
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new UnsupportedException("The row filter needs a query it can parse, and this SQL could not be parsed.");
        }
        if (!(statement instanceof Select select)) {
            throw new UnsupportedException("A row filter applies only to queries that return rows.");
        }
        int[] markers = {0};
        boolean injected = switch (select) {
            case PlainSelect plain -> inject(plain, column, values, markers);
            case SetOperationList union -> union.getSelects().stream().allMatch(s -> s instanceof PlainSelect p
                    && inject(p, column, values, markers));
            default -> false;
        };
        if (injected) {
            return bind(select.toString(), query.parameters(), values);
        }
        if (select.getOrderByElements() != null && !select.getOrderByElements().isEmpty()) {
            throw new UnsupportedException(("Name '%s' in the select list (not only through *), so the filter can keep the "
                    + "query's ORDER BY.").formatted(column));
        }
        String wrapped = "SELECT * FROM (" + sql + ") opendbapi_rf WHERE " + condition("opendbapi_rf." + column, values.size());
        List<Object> params = new ArrayList<>(query.parameters());
        params.addAll(values);
        return new BoundSql(wrapped, params);
    }

    /** Adds the condition to one SELECT's WHERE; false when the column's source is unclear. */
    private static boolean inject(PlainSelect select, String column, List<?> values, int[] markers) {
        Expression source = sourceOf(select, column);
        if (source == null) {
            return false;
        }
        if (aggregates(source)) {
            throw new UnsupportedException(
                    "'%s' is computed by an aggregate or window function; filter on a plain column instead.".formatted(column));
        }
        Expression left = source instanceof Column ? source : new ParenthesedExpressionList<>(source);
        Expression condition;
        if (values.isEmpty()) {
            condition = new EqualsTo(new LongValue(1), new LongValue(0));
        } else {
            Column marker = new Column(MARKER + markers[0]++);
            condition = values.size() == 1 ? new EqualsTo(left, marker)
                    : new InExpression(left, new ParenthesedExpressionList<>(marker));
        }
        Expression where = select.getWhere();
        select.setWhere(where == null ? condition : new AndExpression(new ParenthesedExpressionList<>(where), condition));
        return true;
    }

    /** The expression that produces {@code column} in this SELECT, or null. */
    private static Expression sourceOf(PlainSelect select, String column) {
        boolean star = false;
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression e = item.getExpression();
            if (e instanceof AllColumns && !(e instanceof AllTableColumns)) {
                star = true;
                continue;
            }
            Alias alias = item.getAlias();
            String label = alias != null ? unquote(alias.getName()) : e instanceof Column c ? unquote(c.getColumnName()) : null;
            if (label != null && label.equalsIgnoreCase(column)) {
                return e;
            }
        }
        // SELECT * FROM one_table: the column can only come from that table.
        boolean single = select.getJoins() == null || select.getJoins().isEmpty();
        if (star && single && select.getFromItem() instanceof Table table) {
            String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getFullyQualifiedName();
            return new Column(qualifier + "." + column);
        }
        if (star && single && select.getFromItem() instanceof ParenthesedSelect derived && derived.getAlias() != null) {
            return new Column(derived.getAlias().getName() + "." + column);
        }
        return null;
    }

    private static boolean aggregates(Expression e) {
        boolean[] found = {false};
        e.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Function function, S context) {
                String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
                if (AGGREGATES.contains(name)) {
                    found[0] = true;
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(AnalyticExpression expression, S context) {
                found[0] = true;
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect subquery, S context) {
                found[0] = true; // a scalar subquery may read other rows
                return null;
            }

            @Override
            public <S> Void visit(Select subquery, S context) {
                found[0] = true;
                return null;
            }
        }, null);
        return found[0];
    }

    private static final java.util.Set<String> AGGREGATES = java.util.Set.of("count", "sum", "avg", "min", "max", "array_agg",
            "string_agg", "group_concat", "listagg", "json_agg", "jsonb_agg", "bool_and", "bool_or", "every", "stddev",
            "variance", "median");

    /** Replaces each marker with placeholders and splices the values in at the right index. */
    private static BoundSql bind(String sql, List<Object> parameters, List<?> values) {
        List<Object> params = new ArrayList<>(parameters);
        String out = sql;
        for (int i = 0; ; i++) {
            String marker = MARKER + i;
            int at = out.indexOf(marker);
            if (at < 0) {
                break;
            }
            int before = Placeholders.countBefore(out, at);
            String slots = String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
            out = out.substring(0, at) + slots + out.substring(at + marker.length());
            params.addAll(before, values);
        }
        if (Placeholders.countBefore(out, out.length()) != params.size()) {
            throw new UnsupportedException("The row filter could not line up the query's parameters.");
        }
        return new BoundSql(out, params);
    }

    private static String condition(String left, int values) {
        if (values == 0) {
            return "1 = 0";
        }
        return values == 1 ? left + " = ?" : left + " IN (" + String.join(", ", java.util.Collections.nCopies(values, "?")) + ")";
    }

    static String unquote(String name) {
        if (name != null && name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                return name.substring(1, name.length() - 1);
            }
        }
        return name;
    }

    private static String stripTerminator(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }

    /** Counts JDBC placeholders, skipping string literals, quoted identifiers and comments. */
    static final class Placeholders {
        private Placeholders() {
        }

        static int countBefore(String sql, int end) {
            int count = 0;
            int i = 0;
            end = Math.min(end, sql.length());
            while (i < end) {
                char c = sql.charAt(i);
                if (c == '\'' || c == '"' || c == '`') {
                    int close = sql.indexOf(c, i + 1);
                    while (close >= 0 && close + 1 < sql.length() && sql.charAt(close + 1) == c) {
                        close = sql.indexOf(c, close + 2); // doubled quote inside the literal
                    }
                    i = close < 0 ? end : close + 1;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    int nl = sql.indexOf('\n', i);
                    i = nl < 0 ? end : nl + 1;
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    int close = sql.indexOf("*/", i + 2);
                    i = close < 0 ? end : close + 2;
                } else {
                    if (c == '?') {
                        count++;
                    }
                    i++;
                }
            }
            return count;
        }
    }
}
