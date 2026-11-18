package io.opendbapi.core.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.SecretResolver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * One HikariCP pool per datasource, created on first use and replaced when the datasource
 * changes (spec §3.3). Also tests connections and browses schemas for the editor.
 */
@Component
public class DataSourceRegistry implements DisposableBean {

    public record TestResult(String product, String version, String driver, long latencyMs) {
    }

    public record Table(String schema, String name, String type, String remarks) {
    }

    public record Tables(List<Table> items, boolean truncated) {
    }

    public record TableColumn(String name, String type, int size, boolean nullable, int position) {
    }

    private static final int MAX_TABLES = 2000;
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "pg_catalog", "information_schema", "pg_toast", "sys", "mysql", "performance_schema",
            "system", "outln", "xdb", "ctxsys", "mdsys", "ordsys", "dbsnmp", "appqossys", "audsys", "gsmadmin_internal",
            "lbacsys", "ojvmsys", "olapsys", "wmsys", "dvsys", "gsmcatuser", "remote_scheduler_agent", "db_owner",
            "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader", "db_datawriter",
            "db_denydatareader", "db_denydatawriter", "guest");

    private record Entry(OffsetDateTime version, HikariDataSource pool) {
    }

    private final Map<UUID, Entry> pools = new ConcurrentHashMap<>();
    private final SecretResolver secrets;
    private final HostPolicy hosts;

    private io.micrometer.core.instrument.MeterRegistry meters;

    /** Registers {@code opendbapi_pool_active{datasource}} for each pool (spec §18). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    public DataSourceRegistry(SecretResolver secrets, HostPolicy hosts) {
        this.secrets = secrets;
        this.hosts = hosts;
    }

    private io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets;

    /** Lets passwords be secret:NAME references (spec §4.5). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceSecrets(io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets) {
        this.workspaceSecrets = workspaceSecrets;
    }

    /** A pooled connection; the caller closes it. */
    public Connection connection(Datasource ds) throws SQLException {
        return pool(ds).getConnection();
    }

    public boolean isPooled(UUID id) {
        return pools.containsKey(id);
    }

    private io.opendbapi.core.cluster.ChangeBus changes;

    /** A datasource or its secret changed on any instance: every instance closes its pool. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChanges(io.opendbapi.core.cluster.ChangeBus changes) {
        this.changes = changes;
        changes.on("datasource", id -> {
            if (id == null) {
                pools.keySet().forEach(this::close);
            } else {
                close(id);
            }
        });
    }

    /** Closes the pool on every instance; the next use opens a fresh one with the current settings. */
    public void evict(UUID id) {
        if (changes != null) {
            changes.publish("datasource", id);
        } else {
            close(id);
        }
    }

    private void close(UUID id) {
        Entry entry = pools.remove(id);
        if (entry != null) {
            entry.pool().close();
        }
    }

    /** Opens one connection with unsaved settings, reports the server, and closes it. */
    public TestResult test(JdbcConfig config, String password) {
        config.validate();
        hosts.check(config.host());
        long start = System.nanoTime();
        try (HikariDataSource probe = new HikariDataSource(hikari(config, password, false, "opendbapi-test"));
             Connection c = probe.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            c.isValid(5);
            return new TestResult(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(), meta.getDriverVersion(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (SQLException | RuntimeException e) {
            if (e instanceof ApiProblem p) {
                throw p;
            }
            throw connectionFailed(e);
        }
    }

    public List<String> schemas(Datasource ds) {
        return withMetadata(ds, (meta, cfg) -> {
            if (cfg.dialect().catalogIsDatabase()) {
                return List.of(metadataCatalog(cfg));
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = cfg.dialect() == JdbcDialect.TRINO ? meta.getSchemas(metadataCatalog(cfg), null) : meta.getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (!isSystem(schema)) {
                        out.add(schema);
                    }
                }
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        });
    }

    public Tables tables(Datasource ds, String schema) {
        return withMetadata(ds, (meta, cfg) -> {
            List<Table> out = new ArrayList<>();
            boolean truncated = false;
            try (ResultSet rs = meta.getTables(metadataCatalog(cfg), schemaPattern(cfg, schema), "%", new String[] {"TABLE", "VIEW"})) {
                while (rs.next()) {
                    if (out.size() == MAX_TABLES) {
                        truncated = true;
                        break;
                    }
                    String tableSchema = cfg.dialect().catalogIsDatabase() ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                    if (!isSystem(tableSchema)) {
                        out.add(new Table(tableSchema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), rs.getString("REMARKS")));
                    }
                }
            }
            return new Tables(out, truncated);
        });
    }

    public List<TableColumn> columns(Datasource ds, String schema, String table) {
        return withMetadata(ds, (meta, cfg) -> {
            List<TableColumn> out = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(metadataCatalog(cfg), schemaPattern(cfg, schema), escape(meta, table), "%")) {
                while (rs.next()) {
                    out.add(new TableColumn(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getInt("ORDINAL_POSITION")));
                }
            }
            out.sort((a, b) -> Integer.compare(a.position(), b.position()));
            return out;
        });
    }

    private double active(UUID id) {
        Entry e = pools.get(id);
        var bean = e == null ? null : e.pool().getHikariPoolMXBean();
        return bean == null ? 0 : bean.getActiveConnections();
    }

    @Override
    public void destroy() {
        pools.keySet().forEach(this::close);
    }

    // ------------------------------------------------------------------ internals

    private HikariDataSource pool(Datasource ds) {
        Entry entry = pools.get(ds.id());
        if (entry != null && entry.version().equals(ds.updatedAt())) {
            return entry.pool();
        }
        synchronized (this) {
            entry = pools.get(ds.id());
            if (entry != null && entry.version().equals(ds.updatedAt())) {
                return entry.pool();
            }
            JdbcConfig cfg = ds.config().validate();
            hosts.check(cfg.host());
            HikariDataSource created = new HikariDataSource(
                    hikari(cfg, workspaceSecrets != null ? workspaceSecrets.resolve(ds.workspaceId(), ds.secretRef())
                            : secrets.resolve(ds.secretRef()), ds.readOnly(), "opendbapi-" + ds.id()));
            Entry previous = pools.put(ds.id(), new Entry(ds.updatedAt(), created));
            if (previous != null) {
                previous.pool().close();
            }
            if (meters != null && previous == null) {
                UUID id = ds.id();
                io.micrometer.core.instrument.Gauge.builder("opendbapi.pool.active", this, r -> r.active(id))
                        .description("Connections in use").tag("datasource", ds.name()).register(meters);
            }
            return created;
        }
    }

    private static HikariConfig hikari(JdbcConfig cfg, String password, boolean readOnly, String poolName) {
        HikariConfig h = new HikariConfig();
        h.setPoolName(poolName);
        h.setJdbcUrl(cfg.jdbcUrl());
        h.setUsername(cfg.username());
        h.setPassword(password);
        h.setMaximumPoolSize(cfg.pool().maximumPoolSize());
        h.setMinimumIdle(cfg.pool().minimumIdle());
        h.setConnectionTimeout(cfg.pool().connectionTimeoutMs());
        h.setIdleTimeout(600_000);
        h.setMaxLifetime(1_800_000);
        if (cfg.dialect() == JdbcDialect.CLICKHOUSE) {
            // The driver refuses setReadOnly(true); ClickHouse's own setting refuses writes and
            // DDL but, unlike readonly=1, still lets the driver set per-query settings.
            if (readOnly) {
                h.addDataSourceProperty("clickhouse_setting_readonly", "2");
            }
        } else {
            h.setReadOnly(readOnly);
        }
        if (readOnly && cfg.dialect() == JdbcDialect.TRINO) {
            // Trino enforces read-only only inside a transaction (verified in the engines test):
            // statements then run in READ ONLY transactions the pool rolls back on return.
            h.setAutoCommit(false);
        }
        if (cfg.dialect() == JdbcDialect.STARROCKS || cfg.dialect() == JdbcDialect.DORIS) {
            // They accept SET SESSION TRANSACTION READ ONLY and ignore it. Kept local, the read-only
            // flag makes the MySQL driver itself refuse statements that change data or schema.
            h.addDataSourceProperty("readOnlyPropagatesToServer", "false");
        }
        if (readOnly && cfg.dialect() == JdbcDialect.POSTGRESQL) {
            // PgJDBC honours setReadOnly only inside explicit transactions by default; with
            // autocommit a write would go through. "always" makes the session itself read-only.
            h.addDataSourceProperty("readOnlyMode", "always");
        }
        if (readOnly && (cfg.dialect() == JdbcDialect.MYSQL || cfg.dialect() == JdbcDialect.MARIADB)) {
            // MariaDB Connector/J does not push setReadOnly to the server under autocommit
            // (verified in ReadOnlyEnforcementTest); make the session itself read-only.
            h.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
        }
        // Do not fail pool construction when the database is down; report it on first use instead.
        h.setInitializationFailTimeout(-1);
        cfg.dialect().forcedProperties().forEach(h::addDataSourceProperty);
        return h;
    }

    @FunctionalInterface
    private interface MetadataWork<T> {
        T apply(DatabaseMetaData meta, JdbcConfig cfg) throws SQLException;
    }

    private <T> T withMetadata(Datasource ds, MetadataWork<T> work) {
        try (Connection c = connection(ds)) {
            return work.apply(c.getMetaData(), ds.config());
        } catch (SQLException | RuntimeException e) {
            if (e instanceof ApiProblem p) {
                throw p;
            }
            throw connectionFailed(e);
        }
    }

    /**
     * The catalog metadata calls are scoped to: the database for MySQL-protocol databases
     * (without a StarRocks or Doris {@code catalog.} prefix), the database for SQL Server, the
     * catalog for Trino; none otherwise.
     */
    private static String metadataCatalog(JdbcConfig cfg) {
        String db = cfg.database();
        return switch (cfg.dialect()) {
            case MYSQL, MARIADB, SQLSERVER -> db;
            case STARROCKS, DORIS -> db.substring(db.indexOf('.') + 1);
            case TRINO -> db.contains(".") ? db.substring(0, db.indexOf('.')) : db;
            default -> null;
        };
    }

    private static String schemaPattern(JdbcConfig cfg, String schema) {
        return cfg.dialect().catalogIsDatabase() || schema == null || schema.isBlank() ? null : schema;
    }

    private static String escape(DatabaseMetaData meta, String name) throws SQLException {
        String esc = meta.getSearchStringEscape();
        return esc == null ? name : name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    private static boolean isSystem(String schema) {
        if (schema == null) {
            return false;
        }
        String s = schema.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(s) || s.startsWith("pg_toast") || s.startsWith("pg_temp");
    }

    private static ApiProblem connectionFailed(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return ApiProblem.badRequest("connection_failed", "Could not connect: " + reason);
    }
}
