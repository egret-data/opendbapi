package io.opendbapi.sql.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * Which table columns each result column of a query is computed from (spec §12.1), so masking
 * rules written against a table column also cover aliases and expressions over it. Derived
 * tables are followed; CTEs and anything unresolved yield sources with an unknown table.
 * A column that reads a subquery is {@code opaque}: its sources are not known.
 */
public final class ColumnLineage {

    /** @param table the real table's name, unqualified, or null when it is not known */
    public record Source(String table, String column) {
    }

    /** @param label the result column's name as written, or null for an unnamed expression */
    public record TracedColumn(String label, Set<Source> sources, boolean opaque) {
    }

    /** @param star the query selects {@code *} somewhere at its top level */
    public record Lineage(List<TracedColumn> columns, boolean star) {

        public Optional<TracedColumn> column(String label) {
            return columns.stream().filter(c -> c.label() != null && c.label().equalsIgnoreCase(label)).findFirst();
        }
    }

    private ColumnLineage() {
    }

    /** Empty when the SQL cannot be parsed or is not a query. */
    public static Optional<Lineage> trace(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql.strip().replaceAll(";+\\s*$", ""));
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!(statement instanceof Select select)) {
            return Optional.empty();
        }
        try {
            return Optional.of(of(select, ctes(select, Set.of())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Set<String> ctes(Select select, Set<String> outer) {
        Set<String> names = new HashSet<>(outer);
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                names.add(lower(RowFilters.unquote(w.getAlias().getName())));
            }
        }
        return names;
    }

    private static Lineage of(Select select, Set<String> ctes) {
        return switch (select) {
            case PlainSelect plain -> plain(plain, ctes);
            case SetOperationList union -> union(union, ctes);
            case ParenthesedSelect p -> of(p.getSelect(), ctes(p.getSelect(), ctes));
            default -> throw new IllegalArgumentException("unsupported select");
        };
    }

    /** Labels from the first branch; sources merged by position across all branches. */
    private static Lineage union(SetOperationList union, Set<String> ctes) {
        List<Lineage> branches = union.getSelects().stream().map(s -> of(s, ctes(s, ctes))).toList();
        Lineage first = branches.get(0);
        List<TracedColumn> merged = new ArrayList<>();
        for (int i = 0; i < first.columns().size(); i++) {
            Set<Source> sources = new LinkedHashSet<>();
            boolean opaque = false;
            for (Lineage b : branches) {
                if (i < b.columns().size()) {
                    sources.addAll(b.columns().get(i).sources());
                    opaque |= b.columns().get(i).opaque();
                } else {
                    opaque = true;
                }
            }
            merged.add(new TracedColumn(first.columns().get(i).label(), sources, opaque));
        }
        return new Lineage(merged, branches.stream().anyMatch(Lineage::star));
    }

    private static Lineage plain(PlainSelect select, Set<String> ctes) {
        Map<String, Scope> scopes = new HashMap<>();
        List<FromItem> from = new ArrayList<>();
        if (select.getFromItem() != null) {
            from.add(select.getFromItem());
        }
        if (select.getJoins() != null) {
            select.getJoins().stream().map(Join::getFromItem).forEach(from::add);
        }
        for (FromItem item : from) {
            Scope scope = scope(item, ctes);
            if (scope.key != null) {
                scopes.put(scope.key, scope);
            }
        }
        Scope only = from.size() == 1 && !scopes.isEmpty() ? scopes.values().iterator().next() : null;

        List<TracedColumn> outputs = new ArrayList<>();
        boolean star = false;
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression e = item.getExpression();
            if (e instanceof AllColumns) {
                star = true;
                continue;
            }
            Set<Source> sources = new LinkedHashSet<>();
            boolean[] opaque = {false};
            e.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column column, S context) {
                    sources.addAll(resolve(column, scopes, only));
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect subquery, S context) {
                    opaque[0] = true;
                    return null;
                }

                @Override
                public <S> Void visit(Select subquery, S context) {
                    opaque[0] = true;
                    return null;
                }
            }, null);
            Alias alias = item.getAlias();
            String label = alias != null ? RowFilters.unquote(alias.getName())
                    : e instanceof Column c ? RowFilters.unquote(c.getColumnName()) : null;
            outputs.add(new TracedColumn(label, sources, opaque[0]));
        }
        return new Lineage(outputs, star);
    }

    /** One FROM item: a real table, or a derived table with its own lineage, or unknown. */
    private record Scope(String key, String table, Lineage derived) {
    }

    private static Scope scope(FromItem item, Set<String> ctes) {
        if (item instanceof Table t) {
            String name = RowFilters.unquote(t.getName());
            String key = lower(t.getAlias() != null ? RowFilters.unquote(t.getAlias().getName()) : name);
            boolean cte = t.getSchemaName() == null && ctes.contains(lower(name));
            return new Scope(key, cte ? null : name, null);
        }
        if (item instanceof ParenthesedSelect p) {
            String key = p.getAlias() == null ? null : lower(RowFilters.unquote(p.getAlias().getName()));
            Lineage inner;
            try {
                inner = of(p.getSelect(), ctes(p.getSelect(), ctes));
            } catch (RuntimeException e) {
                inner = null;
            }
            return new Scope(key, null, inner);
        }
        String key = item.getAlias() == null ? null : lower(RowFilters.unquote(item.getAlias().getName()));
        return new Scope(key, null, null);
    }

    private static Set<Source> resolve(Column column, Map<String, Scope> scopes, Scope only) {
        String name = RowFilters.unquote(column.getColumnName());
        Table qualifier = column.getTable();
        Scope scope = qualifier != null && qualifier.getName() != null
                ? scopes.get(lower(RowFilters.unquote(qualifier.getName()))) : only;
        if (scope == null) {
            return Set.of(new Source(null, name));
        }
        if (scope.derived != null) {
            return scope.derived.column(name).map(TracedColumn::sources).filter(s -> !s.isEmpty())
                    .orElse(Set.of(new Source(null, name)));
        }
        return Set.of(new Source(scope.table, name));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
