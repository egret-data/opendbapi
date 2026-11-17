package io.opendbapi.sql.page;

import io.opendbapi.sql.template.BoundSql;
import java.util.ArrayList;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Turns a rendered query into a page of it plus a matching count query (spec §9.3).
 *
 * <p>The page clause is appended when the query's own top level does not already limit
 * itself; otherwise, or when the SQL cannot be parsed, the query is wrapped as a derived
 * table, which is always correct. The count drops a top-level ORDER BY when that is safe,
 * because ordering does not change a count (and SQL Server rejects ORDER BY in a derived table).
 */
public final class Paginator {

    /** The page to fetch and the total-count query for the same filter. */
    public record Paged(BoundSql data, BoundSql count) {
    }

    private Paginator() {
    }

    /**
     * @param page 1-based
     * @throws IllegalArgumentException for a page below 1 or a size below 1
     */
    public static Paged paginate(BoundSql query, Dialect dialect, int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1 or more, got " + page + ".");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be 1 or more, got " + size + ".");
        }
        return window(query, dialect, (long) (page - 1) * size, size);
    }

    /** Rows {@code offset} to {@code offset + limit - 1}, and the count query. */
    public static Paged window(BoundSql query, Dialect dialect, long offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must be 0 or more and limit 1 or more.");
        }
        int size = limit;
        String sql = stripTerminator(query.sql());
        Shape shape = Shape.of(sql);

        boolean append = shape.parsed && !shape.limited;
        String base = append ? sql : "SELECT * FROM (" + sql + ") page_q";
        List<Object> dataParams = new ArrayList<>(query.parameters());
        String data = switch (dialect.style) {
            case LIMIT_OFFSET -> {
                dataParams.add(size);
                dataParams.add(offset);
                yield base + " LIMIT ? OFFSET ?";
            }
            case OFFSET_FETCH, OFFSET_FETCH_ORDERED -> {
                boolean needsOrder = dialect.style == Dialect.Style.OFFSET_FETCH_ORDERED && !(append && shape.ordered);
                dataParams.add(offset);
                dataParams.add(size);
                yield base + (needsOrder ? " ORDER BY (SELECT NULL)" : "") + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            }
        };

        String countInner = shape.unorderedSql != null ? shape.unorderedSql : sql;
        BoundSql count = new BoundSql("SELECT COUNT(*) FROM (" + countInner + ") count_q", query.parameters());
        return new Paged(new BoundSql(data, dataParams), count);
    }

    /** A sort key of keyset pagination: a result column, ascending unless {@code descending}. */
    public record Key(String column, boolean descending) {
        public Key {
            if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Not a plain column name: " + column);
            }
        }
    }

    /**
     * Keyset pagination (spec §9.3): the rows after {@code after} in key order, at most
     * {@code limit} of them. The query becomes a derived table ordered by the keys; its own
     * top-level ORDER BY is dropped. Keys must be result columns that are never null and,
     * together, unique.
     *
     * @param after the last row's key values, or null for the first page
     * @throws IllegalArgumentException when the query cannot be paged this way
     */
    public static BoundSql keyset(BoundSql query, Dialect dialect, List<Key> keys, List<Object> after, int limit) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Keyset pagination needs at least one key column.");
        }
        if (after != null && after.size() != keys.size()) {
            throw new IllegalArgumentException("The cursor does not match the key columns.");
        }
        String sql = stripTerminator(query.sql());
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException("Keyset pagination needs a query it can parse.");
        }
        if (!(statement instanceof Select select)) {
            throw new IllegalArgumentException("Keyset pagination applies only to queries.");
        }
        boolean limited = select.getLimit() != null || select.getOffset() != null || select.getFetch() != null
                || (select instanceof PlainSelect p && p.getTop() != null);
        if (limited) {
            throw new IllegalArgumentException("The query limits itself; remove LIMIT/OFFSET/FETCH/TOP to page it by key.");
        }
        String inner = sql;
        if (select.getOrderByElements() != null && !select.getOrderByElements().isEmpty()) {
            if (select.getOrderByElements().stream().anyMatch(o -> o.toString().contains("?"))) {
                throw new IllegalArgumentException("The query's ORDER BY reads a parameter; keyset pagination orders by its keys instead.");
            }
            select.setOrderByElements(null);
            inner = select.toString();
        }
        List<Object> params = new ArrayList<>(query.parameters());
        StringBuilder out = new StringBuilder("SELECT * FROM (").append(inner).append(") keyset_q");
        if (after != null) {
            // (k1 > ?) OR (k1 = ? AND k2 > ?) OR ...: portable, unlike row-value comparison.
            List<String> ors = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                List<String> ands = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    ands.add("keyset_q." + keys.get(j).column() + " = ?");
                    params.add(after.get(j));
                }
                Key k = keys.get(i);
                ands.add("keyset_q." + k.column() + (k.descending() ? " < ?" : " > ?"));
                params.add(after.get(i));
                ors.add("(" + String.join(" AND ", ands) + ")");
            }
            out.append(" WHERE ").append(String.join(" OR ", ors));
        }
        out.append(" ORDER BY ").append(String.join(", ", keys.stream()
                .map(k -> "keyset_q." + k.column() + (k.descending() ? " DESC" : " ASC")).toList()));
        if (dialect.style == Dialect.Style.LIMIT_OFFSET) {
            out.append(" LIMIT ?");
        } else {
            out.append(" OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        }
        params.add(limit);
        return new BoundSql(out.toString(), params);
    }

    private static String stripTerminator(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }

    /** What the top level of the query looks like, as far as the parser can tell. */
    private record Shape(boolean parsed, boolean limited, boolean ordered, String unorderedSql) {

        static Shape of(String sql) {
            Statement statement;
            try {
                statement = CCJSqlParserUtil.parse(sql);
            } catch (Exception e) {
                return new Shape(false, false, false, null);
            }
            if (!(statement instanceof Select select)) {
                return new Shape(false, false, false, null);
            }
            boolean limited = select.getLimit() != null || select.getOffset() != null || select.getFetch() != null
                    || (select instanceof PlainSelect p && p.getTop() != null);
            boolean ordered = select.getOrderByElements() != null && !select.getOrderByElements().isEmpty();
            String unordered = null;
            if (ordered && !limited && select.getOrderByElements().stream().noneMatch(o -> o.toString().contains("?"))) {
                select.setOrderByElements(null);
                unordered = select.toString();
            }
            return new Shape(true, limited, ordered, unordered);
        }
    }
}
