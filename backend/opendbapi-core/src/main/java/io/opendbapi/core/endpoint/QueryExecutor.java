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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs a compiled task on its datasource (spec §6, §9.1 steps 10–12): statements in order on
 * one connection, bind values only, statement timeout, row cap, optional transaction, and
 * pagination of the main statement. Preview always rolls back, so trying a write is harmless.
 */
@Component
public class QueryExecutor {

    public enum RunMode { SERVE, PREVIEW }

    /**
     * Offset pages ({@code page}, {@code size}, optional count) or, when {@code keys} is not
     * empty, keyset pages: {@code size} rows after the {@code after} key values (null = first page).
     */
    public record PageRequest(int page, int size, boolean count, List<EndpointDefinition.KeysetKey> keys, List<Object> after) {
        public PageRequest {
            keys = keys == null ? List.of() : keys;
        }

        public PageRequest(int page, int size, boolean count) {
            this(page, size, count, null, null);
        }

        public boolean keyset() {
            return !keys.isEmpty();
        }
    }

    public record ResultColumn(String name, String type) {
    }

    /**
     * The main statement's outcome. {@code rows} for queries, {@code affectedRows} for writes;
     * {@code value} is what a full-mode script returned (spec §7.4).
     */
    public record QueryResult(
            List<ResultColumn> columns,
            List<Map<String, Object>> rows,
            Long affectedRows,
            boolean truncated,
            Long total,
            Boolean hasNext,
            long durationMs,
            List<BoundSql> executed,
            Object value) {

        public QueryResult(List<ResultColumn> columns, List<Map<String, Object>> rows, Long affectedRows, boolean truncated, Long total,
                           Boolean hasNext, long durationMs, List<BoundSql> executed) {
            this(columns, rows, affectedRows, truncated, total, hasNext, durationMs, executed, null);
        }
    }

    /**
     * Data policies for a run (spec §12): the row filter rewrites each query, masks change the
     * values each query returns. A full-mode script's queries each go through both.
     */
    public interface Policy {
        Policy NONE = new Policy() {
            @Override
            public BoundSql rewrite(BoundSql query) {
                return query;
            }

            @Override
            public void mask(List<ResultColumn> columns, List<Map<String, Object>> rows, String sql) {
            }
        };

        BoundSql rewrite(BoundSql query);

        /** @param sql the query as written (before the row filter), to trace masked table columns */
        void mask(List<ResultColumn> columns, List<Map<String, Object>> rows, String sql);

        static Policy rewriteOnly(java.util.function.UnaryOperator<BoundSql> rewrite) {
            return new Policy() {
                @Override
                public BoundSql rewrite(BoundSql query) {
                    return rewrite.apply(query);
                }

                @Override
                public void mask(List<ResultColumn> columns, List<Map<String, Object>> rows, String sql) {
                }
            };
        }
    }

    /**
     * Calls another endpoint of the workspace for a workflow's api node (spec §6.1): its live
     * version, as the same caller, with its own data policies. Returns the answer's data.
     */
    public interface EndpointCaller {
        Object call(UUID endpointId, Map<String, Object> params);
    }

    /** At most this many queries per full-script run. */
    static final int MAX_SCRIPT_QUERIES = 100;

    /** The database refused the SQL; {@code detail} is its own message (for authors, not callers). */
    public static class QueryFailedException extends RuntimeException {
        public QueryFailedException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }

    private static final org.slf4j.Logger slow = org.slf4j.LoggerFactory.getLogger("opendbapi.slow");

    private final DataSourceRegistry registry;
    private io.micrometer.core.instrument.MeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private io.micrometer.observation.ObservationRegistry observations = io.micrometer.observation.ObservationRegistry.NOOP;
    private long slowQueryMs = 1000;

    public QueryExecutor(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setObservations(io.micrometer.observation.ObservationRegistry observations) {
        this.observations = observations;
    }

    @org.springframework.beans.factory.annotation.Value("${opendbapi.serving.slow-query-ms:1000}")
    void setSlowQueryMs(long slowQueryMs) {
        this.slowQueryMs = slowQueryMs;
    }

    /**
     * Times database work per datasource, opens a {@code opendbapi.db} observation (a span when
     * tracing is on; never with parameter values) and logs slow runs with the rendered SQL and
     * the parameter names only (spec §18).
     */
    private <T> T measured(Datasource ds, Map<String, Object> params, List<BoundSql> executed,
                           java.util.function.Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return io.micrometer.observation.Observation.createNotStarted("opendbapi.db", observations)
                    .lowCardinalityKeyValue("db.system", ds.config().dialect().name().toLowerCase(java.util.Locale.ROOT))
                    .lowCardinalityKeyValue("datasource", ds.name())
                    .observe(work);
        } finally {
            long nanos = System.nanoTime() - start;
            io.micrometer.core.instrument.Timer.builder("opendbapi.db.duration").description("Database time per call")
                    .tag("datasource", ds.name()).register(meters).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            long ms = nanos / 1_000_000;
            if (ms >= slowQueryMs) {
                slow.warn("Slow query: {} ms on datasource '{}', parameters {}: {}", ms, ds.name(),
                        params == null ? List.of() : new java.util.TreeSet<>(params.keySet()),
                        executed.stream().map(BoundSql::sql).toList());
            }
        }
    }

    public QueryResult run(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode) {
        return run(ds, task, params, page, limits, mode, java.util.function.UnaryOperator.identity());
    }

    /**
     * @param mainRewrite applied to the rendered main statement before pagination, e.g. a row
     *                    filter (spec §9.1 step 11)
     */
    public QueryResult run(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode, java.util.function.UnaryOperator<BoundSql> mainRewrite) {
        return run(ds, task, params, page, limits, mode, Policy.rewriteOnly(mainRewrite));
    }

    /** Like the others, with masks too: full-mode scripts mask each query's values here. */
    public QueryResult run(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode, Policy policy) {
        return run(ds, task, params, page, limits, mode, policy, null);
    }

    /** @param caller runs workflow api nodes; null where calling other endpoints is not available */
    public QueryResult run(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode, Policy policy, EndpointCaller caller) {
        List<BoundSql> executed = new ArrayList<>();
        if (task.isFull()) {
            return measured(ds, params, executed, () -> runScript(ds, task, params, limits, mode, policy, executed));
        }
        if (task.isWorkflow()) {
            return measured(ds, params, executed, () -> runWorkflow(ds, task, params, limits, mode, policy, executed, caller));
        }
        return measured(ds, params, executed, () -> runMeasured(ds, task, params, page, limits, mode, policy::rewrite, executed));
    }

    /**
     * The database as full-mode scripts and workflow nodes reach it (spec §7.4, §6.1): each query
     * is checked, filtered by the row filter, limited and masked. Safe to use from several threads
     * when each thread has its own connection.
     */
    private final class ScriptDb implements io.opendbapi.core.script.DbAccess {
        private final Connection connection;
        private final Datasource ds;
        private final EndpointDefinition.Limits limits;
        private final Policy policy;
        private final List<BoundSql> executed;
        private final java.util.concurrent.atomic.AtomicLong rows;
        private final java.util.concurrent.atomic.AtomicBoolean truncated;
        private final java.util.concurrent.atomic.AtomicBoolean wrote;

        ScriptDb(Connection connection, Datasource ds, EndpointDefinition.Limits limits, Policy policy, List<BoundSql> executed,
                 java.util.concurrent.atomic.AtomicLong rows, java.util.concurrent.atomic.AtomicBoolean truncated,
                 java.util.concurrent.atomic.AtomicBoolean wrote) {
            this.connection = connection;
            this.ds = ds;
            this.limits = limits;
            this.policy = policy;
            this.executed = executed;
            this.rows = rows;
            this.truncated = truncated;
            this.wrote = wrote;
        }

        ScriptDb on(Connection other) {
            return new ScriptDb(other, ds, limits, policy, executed, rows, truncated, wrote);
        }

        /** @throws io.opendbapi.core.script.DbAccess.Failure wrapping what went wrong */
        QueryResult query(BoundSql bound, boolean write) {
            synchronized (executed) {
                if (executed.size() >= MAX_SCRIPT_QUERIES) {
                    throw new io.opendbapi.core.script.DbAccess.Failure(new ApiProblem(500, "script_failed",
                            "More than %d queries ran in one request.".formatted(MAX_SCRIPT_QUERIES)));
                }
            }
            boolean isWrite = io.opendbapi.sql.inspect.SqlInspector.isWrite(bound.sql());
            if (isWrite != write) {
                throw new io.opendbapi.core.script.DbAccess.Failure(new ApiProblem(500, "script_failed", write
                        ? "update runs INSERT, UPDATE or DELETE; use one, list or value for queries."
                        : "one, list and value run queries; use update for INSERT, UPDATE or DELETE."));
            }
            if (isWrite && ds.readOnly()) {
                throw new io.opendbapi.core.script.DbAccess.Failure(new ApiProblem(403, "read_only_datasource",
                        "This endpoint's datasource is read-only."));
            }
            for (var f : io.opendbapi.sql.inspect.SqlInspector.inspect(bound.sql(),
                    new io.opendbapi.sql.inspect.SqlInspector.Context(false, ds.readOnly()))) {
                if (f.severity() == io.opendbapi.sql.inspect.SqlInspector.Severity.ERROR) {
                    throw new io.opendbapi.core.script.DbAccess.Failure(new ScriptFailed("A query was refused: " + f.message()));
                }
            }
            try {
                BoundSql run = isWrite ? bound : policy.rewrite(bound);
                executed.add(run);
                QueryResult r = execute(connection, run, limits);
                if (isWrite) {
                    wrote.set(true);
                } else {
                    policy.mask(r.columns(), r.rows(), bound.sql());
                    rows.addAndGet(r.rows().size());
                    if (r.truncated()) {
                        truncated.set(true);
                    }
                }
                return r;
            } catch (SQLException e) {
                throw new io.opendbapi.core.script.DbAccess.Failure(isTimeout(e)
                        ? new ApiProblem(504, "query_timeout", "A query did not finish within %d seconds.".formatted(limits.timeoutSeconds()))
                        : new QueryFailedException(e.getMessage(), e));
            } catch (ApiProblem e) {
                throw new io.opendbapi.core.script.DbAccess.Failure(e);
            }
        }

        private QueryResult read(io.opendbapi.core.script.SqlFragment q) {
            if (q == null) {
                throw new io.opendbapi.core.script.DbAccess.Failure(new ApiProblem(500, "script_failed", "db needs a query built with sql\"…\"."));
            }
            return query(q.toBoundSql(), false);
        }

        @Override
        public Map<String, Object> one(io.opendbapi.core.script.SqlFragment q) {
            List<Map<String, Object>> r = read(q).rows();
            return r.isEmpty() ? null : r.get(0);
        }

        @Override
        public List<Map<String, Object>> list(io.opendbapi.core.script.SqlFragment q) {
            return read(q).rows();
        }

        @Override
        public Object value(io.opendbapi.core.script.SqlFragment q) {
            List<Map<String, Object>> r = read(q).rows();
            return r.isEmpty() || r.get(0).isEmpty() ? null : r.get(0).values().iterator().next();
        }

        @Override
        public long update(io.opendbapi.core.script.SqlFragment q) {
            if (q == null) {
                throw new io.opendbapi.core.script.DbAccess.Failure(new ApiProblem(500, "script_failed", "db needs a query built with sql\"…\"."));
            }
            Long n = query(q.toBoundSql(), true).affectedRows();
            return n == null ? 0 : n;
        }

        QueryResult finish(Object value, long startNanos) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            return new QueryResult(List.of(), List.of(), wrote.get() ? 0L : null, truncated.get(), null, null, ms, List.copyOf(executed),
                    new ScriptValue(plainJson(value), rows.get(), wrote.get()));
        }
    }

    private ScriptDb scriptDb(Connection c, Datasource ds, EndpointDefinition.Limits limits, Policy policy, List<BoundSql> executed) {
        return new ScriptDb(c, ds, limits, policy, executed, new java.util.concurrent.atomic.AtomicLong(),
                new java.util.concurrent.atomic.AtomicBoolean(), new java.util.concurrent.atomic.AtomicBoolean());
    }

    /** Turns what a script or workflow threw into the answer a caller gets. */
    private static RuntimeException translate(RuntimeException e) {
        if (e instanceof io.opendbapi.core.script.DbAccess.Failure f) {
            return f.unwrap();
        }
        if (e instanceof io.opendbapi.core.script.ScriptAnswer a) {
            return new ApiProblem(a.status(), a.code(), a.getMessage());
        }
        if (e instanceof io.opendbapi.core.script.ScriptRejected r) {
            org.slf4j.LoggerFactory.getLogger(QueryExecutor.class).warn("Endpoint script failed: {}", r.getMessage());
            return new ScriptFailed(r.getMessage());
        }
        return e;
    }

    /**
     * Spec §7.4 full mode: the script runs its queries on one connection (a transaction when
     * the task asks for one, always rolled back in preview).
     */
    private QueryResult runScript(Datasource ds, CompiledTask task, Map<String, Object> params, EndpointDefinition.Limits limits,
                                  RunMode mode, Policy policy, List<BoundSql> executed) {
        long start = System.nanoTime();
        try (Connection c = registry.connection(ds)) {
            boolean manual = mode == RunMode.PREVIEW || task.transaction();
            if (manual) {
                c.setAutoCommit(false);
            }
            ScriptDb db = scriptDb(c, ds, limits, policy, java.util.Collections.synchronizedList(executed));
            try {
                Object value = task.full().run(params, db);
                QueryResult result = db.finish(value, start);
                if (mode == RunMode.PREVIEW) {
                    c.rollback();
                } else if (manual) {
                    c.commit();
                }
                return result;
            } catch (RuntimeException e) {
                if (manual) {
                    rollbackQuietly(c);
                }
                throw translate(e);
            }
        } catch (SQLException e) {
            throw new QueryFailedException(e.getMessage(), e);
        }
    }

    /**
     * Spec §6.1: runs the workflow level by level. Nodes of one level run in parallel, each on
     * its own connection, unless the task is a transaction or a preview: then everything runs in
     * order on one connection, committed or rolled back together.
     */
    private QueryResult runWorkflow(Datasource ds, CompiledTask task, Map<String, Object> params, EndpointDefinition.Limits limits,
                                    RunMode mode, Policy policy, List<BoundSql> executed, EndpointCaller caller) {
        long start = System.nanoTime();
        CompiledWorkflow wf = task.workflow();
        Map<String, Object> results = new java.util.concurrent.ConcurrentHashMap<>();
        Set<String> inactive = java.util.concurrent.ConcurrentHashMap.newKeySet();
        boolean together = mode == RunMode.PREVIEW || task.transaction();
        List<BoundSql> shared = java.util.Collections.synchronizedList(executed);
        try (Connection c = registry.connection(ds)) {
            if (together) {
                c.setAutoCommit(false);
            }
            ScriptDb db = scriptDb(c, ds, limits, policy, shared);
            try {
                for (List<CompiledWorkflow.Node> level : wf.levels()) {
                    if (together || level.size() == 1) {
                        for (CompiledWorkflow.Node n : level) {
                            runNode(n, params, results, inactive, db, caller);
                        }
                    } else {
                        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
                        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                            for (CompiledWorkflow.Node n : level) {
                                running.add(pool.submit(() -> {
                                    try (Connection own = registry.connection(ds)) {
                                        runNode(n, params, results, inactive, db.on(own), caller);
                                    } catch (SQLException e) {
                                        throw new QueryFailedException(e.getMessage(), e);
                                    }
                                    return null;
                                }));
                            }
                            for (var f : running) {
                                try {
                                    f.get();
                                } catch (java.util.concurrent.ExecutionException e) {
                                    running.forEach(x -> x.cancel(true));
                                    throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new ApiProblem(503, "interrupted", "The request was interrupted.");
                                }
                            }
                        }
                    }
                }
                QueryResult result = db.finish(answer(wf, results, inactive), start);
                if (mode == RunMode.PREVIEW) {
                    c.rollback();
                } else if (together) {
                    c.commit();
                }
                return result;
            } catch (RuntimeException e) {
                if (together) {
                    rollbackQuietly(c);
                }
                throw translate(e);
            }
        } catch (SQLException e) {
            throw new QueryFailedException(e.getMessage(), e);
        }
    }

    /** Stands for a null result in concurrent maps. */
    private static final Object NULL = new Object();

    /** The output node's result, or else the result of the last sink that ran (e.g. the end node of the branch taken). */
    private static Object answer(CompiledWorkflow wf, Map<String, Object> results, Set<String> inactive) {
        String id = wf.output();
        if (id == null) {
            List<CompiledWorkflow.Node> nodes = wf.nodes();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                if (nodes.get(i).sink() && !inactive.contains(nodes.get(i).id())) {
                    id = nodes.get(i).id();
                    break;
                }
            }
        }
        Object v = id == null ? null : results.get(id);
        return v == NULL ? null : v;
    }

    private static void runNode(CompiledWorkflow.Node n, Map<String, Object> params, Map<String, Object> results, Set<String> inactive,
                                ScriptDb db, EndpointCaller caller) {
        // A node on a branch not taken, or after one, does not run (spec §6.1).
        for (CompiledWorkflow.Dep d : n.dependsOn()) {
            if (inactive.contains(d.id()) || (d.branch() != null && !d.branch().equals(results.get(d.id())))) {
                inactive.add(n.id());
                return;
            }
        }
        Map<String, Object> context = new LinkedHashMap<>(params);
        results.forEach((k, v) -> context.put(k, v == NULL ? null : v));
        if (n.when() != null && !n.when().test(context::get)) {
            results.put(n.id(), NULL);
            return;
        }
        Object value;
        if (n.kind() == CompiledWorkflow.Kind.DECISION) {
            value = n.condition().test(context::get);
        } else if (n.kind() == CompiledWorkflow.Kind.END) {
            value = n.answer().evaluate(context::get);
        } else if (n.kind() == CompiledWorkflow.Kind.API) {
            if (caller == null) {
                throw new ApiProblem(500, "workflow_failed", "Node '%s' calls another endpoint, which is not available here.".formatted(n.id()));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            n.params().forEach((name, e) -> values.put(name, e.evaluate(context::get)));
            try {
                value = caller.call(n.endpointId(), values);
            } catch (ApiProblem e) {
                throw new ApiProblem(e.status(), e.code(), "Node '%s': %s".formatted(n.id(), e.getMessage()));
            }
        } else if (n.script() != null) {
            value = n.script().run(context, db);
        } else {
            BoundSql bound;
            try {
                bound = n.sql().render(context);
            } catch (io.opendbapi.sql.template.TemplateException e) {
                throw new ApiProblem(500, "template_failed", "Node '%s': %s".formatted(n.id(), e.getMessage()));
            }
            QueryResult r = db.query(bound, "update".equals(n.result()));
            value = switch (n.result()) {
                case "one" -> r.rows().isEmpty() ? null : r.rows().get(0);
                case "value" -> r.rows().isEmpty() || r.rows().get(0).isEmpty() ? null : r.rows().get(0).values().iterator().next();
                case "update" -> r.affectedRows();
                default -> r.rows();
            };
        }
        results.put(n.id(), value == null ? NULL : value);
    }

    /** What a full-mode script returned, with how many rows its queries read and whether it wrote. */
    public record ScriptValue(Object data, long rows, boolean wrote) {
    }

    /** Groovy strings become plain strings, so the answer serialises as JSON text. */
    private static Object plainJson(Object v) {
        if (v instanceof groovy.lang.GString || v instanceof CharSequence) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plainJson(x)));
            return out;
        }
        if (v instanceof java.util.Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(x -> out.add(plainJson(x)));
            return out;
        }
        if (v instanceof io.opendbapi.core.script.SqlFragment) {
            throw new ScriptFailed("A full-mode script returns data, not a sql\"…\" fragment; run it with db.list(…).");
        }
        return v;
    }

    private QueryResult runMeasured(Datasource ds, CompiledTask task, Map<String, Object> params, PageRequest page,
                      EndpointDefinition.Limits limits, RunMode mode, java.util.function.UnaryOperator<BoundSql> mainRewrite,
                      List<BoundSql> executed) {
        long start = System.nanoTime();
        Dialect dialect = Dialect.valueOf(ds.config().dialect().name());
        try (Connection c = registry.connection(ds)) {
            boolean manual = mode == RunMode.PREVIEW || task.transaction();
            if (manual) {
                c.setAutoCommit(false);
            }
            try {
                QueryResult main = null;
                for (int i = 0; i < task.statements().size(); i++) {
                    boolean isMain = i == task.mainIndex();
                    BoundSql bound = render(task.statements().get(i), params);
                    if (isMain) {
                        bound = mainRewrite.apply(bound);
                    }
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
        return stream(ds, task, params, limits, sink, java.util.function.UnaryOperator.identity());
    }

    public long stream(Datasource ds, CompiledTask task, Map<String, Object> params, EndpointDefinition.Limits limits,
                       RowSink sink, java.util.function.UnaryOperator<BoundSql> mainRewrite) {
        if (task.isFull() || task.isWorkflow()) {
            throw ApiProblem.badRequest("not_streamable", "Script and workflow endpoints answer JSON only.");
        }
        List<BoundSql> executed = new ArrayList<>();
        return measured(ds, params, executed, () -> streamMeasured(ds, task, params, limits, sink, mainRewrite, executed));
    }

    private long streamMeasured(Datasource ds, CompiledTask task, Map<String, Object> params, EndpointDefinition.Limits limits,
                                RowSink sink, java.util.function.UnaryOperator<BoundSql> mainRewrite, List<BoundSql> executed) {
        int limit = limits.streamRowLimit();
        try (Connection c = registry.connection(ds)) {
            c.setAutoCommit(false); // PostgreSQL only uses a cursor (fetch size) inside a transaction
            boolean started = false;
            try {
                for (int i = 0; i < task.statements().size(); i++) {
                    BoundSql bound = render(task.statements().get(i), params);
                    if (i == task.mainIndex()) {
                        bound = mainRewrite.apply(bound);
                    }
                    executed.add(bound);
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
        } catch (io.opendbapi.core.script.ScriptRejected e) {
            // The author's problem, not the caller's: details go to the log (and to preview).
            org.slf4j.LoggerFactory.getLogger(QueryExecutor.class).warn("Endpoint script failed: {}", e.getMessage());
            throw new ScriptFailed(e.getMessage());
        }
    }

    /** A script failed while rendering; {@link #getMessage()} is for the endpoint's authors. */
    public static final class ScriptFailed extends ApiProblem {
        public ScriptFailed(String detail) {
            super(500, "script_failed", detail);
        }
    }

    private QueryResult paged(Connection c, BoundSql bound, Dialect dialect, PageRequest page, EndpointDefinition.Limits limits,
                         List<BoundSql> executed) throws SQLException {
        if (page.keyset()) {
            BoundSql data;
            try {
                data = Paginator.keyset(bound, dialect,
                        page.keys().stream().map(k -> new Paginator.Key(k.column(), k.descending())).toList(), page.after(), page.size() + 1);
            } catch (IllegalArgumentException e) {
                throw new ApiProblem(500, "keyset_failed", "This endpoint's keyset pagination cannot page its query: " + e.getMessage());
            }
            executed.add(data);
            QueryResult result = execute(c, data, limits);
            boolean hasNext = result.rows().size() > page.size();
            List<Map<String, Object>> rows = hasNext ? result.rows().subList(0, page.size()) : result.rows();
            return new QueryResult(result.columns(), rows, null, result.truncated(), null, hasNext, 0, executed);
        }
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
