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

    public record Column(String name, String type, int size, boolean nullable, int position) {
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

    public DataSourceRegistry(SecretResolver secrets, HostPolicy hosts) {
        this.secrets = secrets;
        this.hosts = hosts;
    }

    /** A pooled connection; the caller closes it. */
    public Connection connection(Datasource ds) throws SQLException {
        return pool(ds).getConnection();
    }

    public boolean isPooled(UUID id) {
        return pools.containsKey(id);
    }

    /** Closes the pool; the next use opens a fresh one with the current settings. */
    public void evict(UUID id) {
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
            if (catalogIsSchema(cfg.dialect())) {
                return List.of(cfg.database());
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = meta.getSchemas()) {
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
            try (ResultSet rs = meta.getTables(catalog(cfg), schemaPattern(cfg, schema), "%", new String[] {"TABLE", "VIEW"})) {
                while (rs.next()) {
                    if (out.size() == MAX_TABLES) {
                        truncated = true;
                        break;
                    }
                    String tableSchema = catalogIsSchema(cfg.dialect()) ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                    if (!isSystem(tableSchema)) {
                        out.add(new Table(tableSchema, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), rs.getString("REMARKS")));
                    }
                }
            }
            return new Tables(out, truncated);
        });
    }

    public List<Column> columns(Datasource ds, String schema, String table) {
        return withMetadata(ds, (meta, cfg) -> {
            List<Column> out = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(catalog(cfg), schemaPattern(cfg, schema), escape(meta, table), "%")) {
                while (rs.next()) {
                    out.add(new Column(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getInt("ORDINAL_POSITION")));
                }
            }
            out.sort((a, b) -> Integer.compare(a.position(), b.position()));
            return out;
        });
    }

    @Override
    public void destroy() {
        pools.keySet().forEach(this::evict);
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
                    hikari(cfg, secrets.resolve(ds.secretRef()), ds.readOnly(), "opendbapi-" + ds.id()));
            Entry previous = pools.put(ds.id(), new Entry(ds.updatedAt(), created));
            if (previous != null) {
                previous.pool().close();
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
        h.setReadOnly(readOnly);
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

    /** MySQL and MariaDB call a database a catalog and have no schemas. */
    private static boolean catalogIsSchema(JdbcDialect dialect) {
        return dialect == JdbcDialect.MYSQL || dialect == JdbcDialect.MARIADB;
    }

    private static String catalog(JdbcConfig cfg) {
        return catalogIsSchema(cfg.dialect()) || cfg.dialect() == JdbcDialect.SQLSERVER ? cfg.database() : null;
    }

    private static String schemaPattern(JdbcConfig cfg, String schema) {
        return catalogIsSchema(cfg.dialect()) || schema == null || schema.isBlank() ? null : schema;
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
