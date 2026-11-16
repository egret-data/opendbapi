package io.opendbapi.core.endpoint;

import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.sql.page.Dialect;
import io.opendbapi.sql.page.Paginator;
import io.opendbapi.sql.template.BoundSql;
import io.opendbapi.sql.template.TemplateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Runs a compiled task on its datasource (spec §6, §9.1 steps 10–12): statements in order on
 * one connection, bind values only, statement timeout, row cap, optional transaction, and
 * pagination of the main statement. Preview always rolls back, so trying a write is harmless.
 */
@Component
public class QueryExecutor {

    public enum RunMode { SERVE, PREVIEW }

    public record PageRequest(int page, int size, boolean count) {
    }

    public record ResultColumn(String name, String type) {
    }

    /** The main statement's outcome. {@code rows} for queries, {@code affectedRows} for writes. */
    public record QueryResult(
            List<ResultColumn> columns,
            List<Map<String, Object>> rows,
            Long affectedRows,
            boolean truncated,
            Long total,
            Boolean hasNext,
            long durationMs,
            List<BoundSql> executed) {
    }

    /** The database refused the SQL; {@code detail} is its own message (for authors, not callers). */
    public static class QueryFailedException extends RuntimeException {
        public QueryFailedException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }

    private final DataSourceRegistry registry;

    public QueryExecutor(DataSourceRegistry registry) {
        this.registry = registry;
    }

    public QueryResult run(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode) {
        long start = System.nanoTime();
        List<BoundSql> executed = new ArrayList<>();
        Dialect dialect = Dialect.valueOf(ds.config().dialect().name());
        try (Connection c = registry.connection(ds)) {
            boolean manual = mode == RunMode.PREVIEW || task.transaction();
            if (manual) {
                c.setAutoCommit(false);
            }
            try {
                QueryResult main = null;
                for (int i = 0; i < task.statements().size(); i++) {
                    BoundSql bound = render(task.statements().get(i), params);
                    boolean isMain = i == task.mainIndex();
                    if (isMain && page != null) {
                        main = paged(c, bound, dialect, page, limits, executed);
                    } else {
                        executed.add(bound);
                        QueryResult r = execute(c, bound, limits);
                        if (isMain) {
                            main = r;
                        }
                    }
                }
                if (mode == RunMode.PREVIEW) {
                    c.rollback();
                } else if (manual) {
                    c.commit();
                }
                long ms = (System.nanoTime() - start) / 1_000_000;
                return new QueryResult(main.columns(), main.rows(), main.affectedRows(), main.truncated(), main.total(),
                        main.hasNext(), ms, executed);
            } catch (SQLException | RuntimeException e) {
                if (manual) {
                    rollbackQuietly(c);
                }
                throw e;
            }
        } catch (SQLTimeoutException e) {
            throw new ApiProblem(504, "query_timeout",
                    "The query did not finish within %d seconds.".formatted(limits.timeoutSeconds()));
        } catch (SQLException e) {
            if (isTimeout(e)) {
                throw new ApiProblem(504, "query_timeout",
                        "The query did not finish within %d seconds.".formatted(limits.timeoutSeconds()));
            }
            throw new QueryFailedException(e.getMessage(), e);
        }
    }

    /** Receives a streamed result. {@link #start} is called only once the query has run. */
    public interface RowSink {
        void start(List<ResultColumn> columns) throws java.io.IOException;

        void row(Object[] values) throws java.io.IOException;

        /** @param truncated more rows existed than the stream limit */
        void end(boolean truncated) throws java.io.IOException;

        /** The query failed after rows were sent: there is no status code left to change. */
        void failed(String code) throws java.io.IOException;
    }

    /** Thrown when the caller went away mid-stream; nothing more can be sent. */
    public static final class ClientGone extends RuntimeException {
        ClientGone(java.io.IOException cause) {
            super(cause);
        }
    }

    /**
     * Streams the main statement (spec §9.6): forward-only cursor with a fetch size, rows handed
     * to the sink as they are read. Errors before the first row surface as usual (problem
     * responses); later ones go to {@link RowSink#failed}. Returns the number of rows sent.
     */
    public long stream(Datasource ds, CompiledTask task, Map<String, Object> params, EndpointDefinition.Limits limits,
                       RowSink sink) {
        int limit = limits.streamRowLimit();
        try (Connection c = registry.connection(ds)) {
            c.setAutoCommit(false); // PostgreSQL only uses a cursor (fetch size) inside a transaction
            boolean started = false;
            try {
                for (int i = 0; i < task.statements().size(); i++) {
                    BoundSql bound = render(task.statements().get(i), params);
                    if (i != task.mainIndex()) {
                        execute(c, bound, limits);
                        continue;
                    }
                    try (PreparedStatement ps = c.prepareStatement(bound.sql(), ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY)) {
                        ps.setQueryTimeout(limits.timeoutSeconds());
                        ps.setFetchSize(1000);
                        ps.setMaxRows(limit + 1);
                        for (int p = 0; p < bound.parameters().size(); p++) {
                            ps.setObject(p + 1, bound.parameters().get(p));
                        }
                        if (!ps.execute()) {
                            throw ApiProblem.badRequest("not_a_query", "Only a query that returns rows can be streamed.");
                        }
                        try (ResultSet rs = ps.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int n = meta.getColumnCount();
                            List<ResultColumn> columns = new ArrayList<>(n);
                            for (int k = 1; k <= n; k++) {
                                columns.add(new ResultColumn(meta.getColumnLabel(k), meta.getColumnTypeName(k)));
                            }
                            sink.start(columns);
                            started = true;
                            long sent = 0;
                            Object[] values = new Object[n];
                            boolean truncated = false;
                            while (rs.next()) {
                                if (sent == limit) {
                                    truncated = true;
                                    break;
                                }
                                for (int k = 1; k <= n; k++) {
                                    values[k - 1] = JdbcValues.read(rs, k);
                                }
                                sink.row(values);
                                sent++;
                            }
                            c.commit();
                            sink.end(truncated);
                            return sent;
                        }
                    }
                }
                throw new IllegalStateException("No main statement");
            } catch (java.io.IOException e) {
                rollbackQuietly(c);
                throw new ClientGone(e);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                if (started && e instanceof SQLException) {
                    try {
                        sink.failed(isTimeout((SQLException) e) ? "query_timeout" : "query_failed");
                    } catch (java.io.IOException ignored) {
                        // the caller is gone too
                    }
                    throw new QueryFailedException(e.getMessage(), (SQLException) e);
                }
                throw e;
            }
        } catch (SQLTimeoutException e) {
            throw new ApiProblem(504, "query_timeout",
                    "The query did not finish within %d seconds.".formatted(limits.timeoutSeconds()));
        } catch (SQLException e) {
            if (isTimeout(e)) {
                throw new ApiProblem(504, "query_timeout",
                        "The query did not finish within %d seconds.".formatted(limits.timeoutSeconds()));
            }
            throw new QueryFailedException(e.getMessage(), e);
        }
    }

    private static BoundSql render(CompiledTask.CompiledStatement s, Map<String, Object> params) {
        try {
            return s.template().render(params);
        } catch (TemplateException e) {
            throw ApiProblem.badRequest("invalid_param", e.getMessage());
        }
    }

    private QueryResult paged(Connection c, BoundSql bound, Dialect dialect, PageRequest page, EndpointDefinition.Limits limits,
                         List<BoundSql> executed) throws SQLException {
        Paginator.Paged paged = Paginator.paginate(bound, dialect, page.page(), page.size());
        Long total = null;
        if (page.count()) {
            executed.add(paged.count());
            QueryResult count = execute(c, paged.count(), limits);
            Object n = count.rows().isEmpty() ? 0 : count.rows().get(0).values().iterator().next();
            total = n instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(n));
        }
        // Without a count, one extra row tells whether another page exists.
        long offset = (long) (page.page() - 1) * page.size();
        BoundSql data = page.count() ? paged.data()
                : Paginator.window(bound, dialect, offset, page.size() + 1).data();
        executed.add(data);
        QueryResult result = execute(c, data, limits);
        List<Map<String, Object>> rows = result.rows();
        boolean hasNext;
        if (total != null) {
            hasNext = offset + page.size() < total;
        } else {
            hasNext = rows.size() > page.size();
            rows = hasNext ? rows.subList(0, page.size()) : rows;
        }
        return new QueryResult(result.columns(), rows, null, result.truncated(), total, hasNext, 0, executed);
    }

    private static QueryResult execute(Connection c, BoundSql bound, EndpointDefinition.Limits limits) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(bound.sql())) {
            ps.setQueryTimeout(limits.timeoutSeconds());
            ps.setMaxRows(limits.maxRows() + 1);
            for (int i = 0; i < bound.parameters().size(); i++) {
                ps.setObject(i + 1, bound.parameters().get(i));
            }
            if (!ps.execute()) {
                return new QueryResult(List.of(), List.of(), (long) ps.getUpdateCount(), false, null, null, 0, List.of());
            }
            try (ResultSet rs = ps.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                List<ResultColumn> columns = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(new ResultColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == limits.maxRows()) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>(n * 2);
                    for (int i = 1; i <= n; i++) {
                        row.put(columns.get(i - 1).name(), JdbcValues.read(rs, i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, null, truncated, null, null, 0, List.of());
            }
        }
    }

    private static boolean isTimeout(SQLException e) {
        String state = e.getSQLState();
        return "57014".equals(state) || "HYT00".equals(state) || "HY008".equals(state)
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"));
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the original failure matters more
        }
    }
}
