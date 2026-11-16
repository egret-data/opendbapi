package io.opendbapi.core.datasource;

import io.opendbapi.core.error.ApiProblem;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The non-secret settings of a JDBC datasource, stored as {@code datasource.config}. The
 * password is a separate secret reference (spec §4.6).
 */
public record JdbcConfig(
        JdbcDialect dialect,
        String host,
        Integer port,
        String database,
        String username,
        Map<String, String> properties,
        Pool pool) {

    /** HikariCP settings, bounded so one workspace cannot exhaust the server. */
    public record Pool(Integer maximumPoolSize, Integer minimumIdle, Integer connectionTimeoutMs) {
        public static final int MAX_POOL_SIZE = 50;
        public static final int MAX_CONNECTION_TIMEOUT_MS = 60_000;

        public Pool {
            maximumPoolSize = maximumPoolSize == null ? 10 : maximumPoolSize;
            minimumIdle = minimumIdle == null ? 0 : minimumIdle;
            connectionTimeoutMs = connectionTimeoutMs == null ? 10_000 : connectionTimeoutMs;
        }
    }

    private static final Pattern HOSTNAME = Pattern.compile("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$");
    private static final Pattern IPV6 = Pattern.compile("^\\[[0-9A-Fa-f:.]+]$");
    private static final Pattern DATABASE = Pattern.compile("^[A-Za-z0-9_$][A-Za-z0-9_$.-]{0,127}$");
    private static final Pattern PROPERTY_VALUE = Pattern.compile("^[A-Za-z0-9_.,:/+ -]{0,200}$");
    private static final Pattern USERNAME = Pattern.compile("^[^\\s;&?]{1,128}$");

    public JdbcConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        pool = pool == null ? new Pool(null, null, null) : pool;
    }

    public int effectivePort() {
        return port == null ? dialect.defaultPort() : port;
    }

    public String jdbcUrl() {
        validate();
        return dialect.url(host, effectivePort(), database, properties);
    }

    /** @throws ApiProblem (400) naming the first invalid field */
    public JdbcConfig validate() {
        if (dialect == null) {
            throw invalid("dialect is required.");
        }
        if (host == null || !(HOSTNAME.matcher(host).matches() || IPV6.matcher(host).matches())) {
            throw invalid("host must be a host name or IP address, without port, path or parameters.");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw invalid("port must be between 1 and 65535.");
        }
        if (database == null || !DATABASE.matcher(database).matches() || database.contains("..")) {
            throw invalid("database may contain letters, digits and _ $ . - only (max 128).");
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw invalid("username is required and may not contain spaces, ; & or ?.");
        }
        for (var e : properties.entrySet()) {
            if (!dialect.allowedProperties().contains(e.getKey())) {
                throw invalid("Driver property '%s' is not allowed for %s. Allowed: %s."
                        .formatted(e.getKey(), dialect, dialect.allowedProperties().isEmpty()
                                ? "none" : String.join(", ", new java.util.TreeSet<>(dialect.allowedProperties()))));
            }
            if (!PROPERTY_VALUE.matcher(e.getValue()).matches()) {
                throw invalid("Driver property '%s' has characters that are not allowed.".formatted(e.getKey()));
            }
        }
        if (pool.maximumPoolSize() < 1 || pool.maximumPoolSize() > Pool.MAX_POOL_SIZE) {
            throw invalid("pool.maximumPoolSize must be between 1 and %d.".formatted(Pool.MAX_POOL_SIZE));
        }
        if (pool.minimumIdle() < 0 || pool.minimumIdle() > pool.maximumPoolSize()) {
            throw invalid("pool.minimumIdle must be between 0 and maximumPoolSize.");
        }
        if (pool.connectionTimeoutMs() < 250 || pool.connectionTimeoutMs() > Pool.MAX_CONNECTION_TIMEOUT_MS) {
            throw invalid("pool.connectionTimeoutMs must be between 250 and %d.".formatted(Pool.MAX_CONNECTION_TIMEOUT_MS));
        }
        return this;
    }

    private static ApiProblem invalid(String detail) {
        return ApiProblem.badRequest("invalid_datasource", detail);
    }
}
