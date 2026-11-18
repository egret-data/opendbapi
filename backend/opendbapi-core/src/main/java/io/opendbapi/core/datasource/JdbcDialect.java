package io.opendbapi.core.datasource;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Supported JDBC databases (spec §4.6). The URL is always built from structured fields, never
 * typed by a user, and only the listed driver properties may be set: several drivers turn
 * URL properties into class loading, file access or deserialisation, which would let a
 * workspace admin run code on the OpenDBAPI server.
 */
public enum JdbcDialect {

    POSTGRESQL(5432, Set.of("sslmode", "ssl", "currentSchema", "ApplicationName", "connectTimeout", "socketTimeout",
            "options", "prepareThreshold", "targetServerType", "loginTimeout", "tcpKeepAlive"),
            Map.of()),
    MYSQL(3306, Set.of("sslMode", "useSSL", "requireSSL", "serverTimezone", "characterEncoding", "connectTimeout",
            "socketTimeout", "zeroDateTimeBehavior", "useUnicode", "tinyInt1isBit", "connectionTimeZone"),
            Map.of("allowLoadLocalInfile", "false", "allowUrlInLocalInfile", "false", "autoDeserialize", "false",
                    "allowPublicKeyRetrieval", "false")),
    MARIADB(3306, Set.of("sslMode", "connectTimeout", "socketTimeout", "useServerPrepStmts", "timezone"),
            Map.of("allowLocalInfile", "false")),
    SQLSERVER(1433, Set.of("encrypt", "trustServerCertificate", "hostNameInCertificate", "loginTimeout",
            "applicationName", "applicationIntent", "socketTimeout", "multiSubnetFailover"),
            Map.of()),
    ORACLE(1521, Set.of(), Map.of()),
    /**
     * Trino's own driver. The database field is a catalog, or {@code catalog.schema}. No file,
     * proxy, Kerberos or external-authentication properties: they read local files or open
     * connections the workspace admin chooses.
     */
    TRINO(8080, Set.of("SSL", "SSLVerification", "source", "clientTags", "applicationNamePrefix", "timezone",
            "explicitPrepare", "disableCompression"),
            Map.of()),
    /**
     * StarRocks speaks the MySQL protocol and is reached through the MySQL driver, with the
     * same forced safety properties. The database field may be {@code catalog.database}.
     */
    STARROCKS(9030, Set.of("sslMode", "connectTimeout", "socketTimeout", "serverTimezone", "connectionTimeZone",
            "characterEncoding", "useUnicode", "zeroDateTimeBehavior", "tinyInt1isBit"),
            MYSQL.forcedProperties),
    /** Apache Doris: as {@link #STARROCKS}. */
    DORIS(9030, STARROCKS.allowedProperties, MYSQL.forcedProperties),
    /**
     * ClickHouse's own driver over HTTP. No trust store, proxy, header or server-setting
     * properties: the last would let a datasource lift the read-only setting.
     */
    CLICKHOUSE(8123, Set.of("ssl", "compress", "client_name", "socket_timeout", "connection_timeout", "max_execution_time",
            "use_server_time_zone", "use_time_zone"),
            Map.of());

    private final int defaultPort;
    private final Set<String> allowedProperties;
    private final Map<String, String> forcedProperties;

    JdbcDialect(int defaultPort, Set<String> allowedProperties, Map<String, String> forcedProperties) {
        this.defaultPort = defaultPort;
        this.allowedProperties = allowedProperties;
        this.forcedProperties = forcedProperties;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public Set<String> allowedProperties() {
        return allowedProperties;
    }

    /** Set on every connection, after (and therefore over) anything else. */
    public Map<String, String> forcedProperties() {
        return forcedProperties;
    }

    /**
     * Whether a rollback undoes writes. StarRocks and Doris accept ROLLBACK but keep the rows;
     * ClickHouse's driver refuses manual commit. On these, preview cannot undo a write and an
     * endpoint cannot run in a transaction.
     */
    public boolean transactional() {
        return switch (this) {
            case STARROCKS, DORIS, CLICKHOUSE -> false;
            default -> true;
        };
    }

    /** Trino commonly runs without authentication, and refuses a password without TLS. */
    public boolean passwordOptional() {
        return this == TRINO;
    }

    /** MySQL-protocol databases call a database a catalog and have no schemas. */
    public boolean catalogIsDatabase() {
        return switch (this) {
            case MYSQL, MARIADB, STARROCKS, DORIS -> true;
            default -> false;
        };
    }

    /** A user-facing name, for messages. */
    public String label() {
        return switch (this) {
            case POSTGRESQL -> "PostgreSQL";
            case MYSQL -> "MySQL";
            case MARIADB -> "MariaDB";
            case SQLSERVER -> "SQL Server";
            case ORACLE -> "Oracle";
            case TRINO -> "Trino";
            case STARROCKS -> "StarRocks";
            case DORIS -> "Apache Doris";
            case CLICKHOUSE -> "ClickHouse";
        };
    }

    String url(String host, int port, String database, Map<String, String> properties) {
        StringBuilder url = new StringBuilder();
        switch (this) {
            case POSTGRESQL, MYSQL, MARIADB, STARROCKS, DORIS, CLICKHOUSE -> {
                String scheme = switch (this) {
                    case STARROCKS, DORIS -> "mysql";
                    default -> name().toLowerCase(java.util.Locale.ROOT);
                };
                url.append("jdbc:").append(scheme).append("://").append(host).append(':').append(port).append('/').append(database);
                appendQuery(url, properties);
            }
            case TRINO -> {
                url.append("jdbc:trino://").append(host).append(':').append(port).append('/').append(database.replace('.', '/'));
                appendQuery(url, properties);
            }
            case SQLSERVER -> {
                url.append("jdbc:sqlserver://").append(host).append(':').append(port)
                        .append(";databaseName=").append(database);
                new TreeMap<>(properties).forEach((k, v) -> url.append(';').append(k).append('=').append(v));
            }
            case ORACLE -> url.append("jdbc:oracle:thin:@//").append(host).append(':').append(port).append('/').append(database);
        }
        return url.toString();
    }

    private static void appendQuery(StringBuilder url, Map<String, String> properties) {
        String sep = "?";
        for (var e : new TreeMap<>(properties).entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(e.getValue());
            sep = "&";
        }
    }
}
