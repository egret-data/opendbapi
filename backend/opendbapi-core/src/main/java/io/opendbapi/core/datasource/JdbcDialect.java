package io.opendbapi.core.datasource;

import java.util.Map;
import java.util.Set;

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
    ORACLE(1521, Set.of(), Map.of());

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

    String url(String host, int port, String database, Map<String, String> properties) {
        StringBuilder url = new StringBuilder();
        switch (this) {
            case POSTGRESQL, MYSQL, MARIADB -> {
                url.append("jdbc:").append(name().toLowerCase(java.util.Locale.ROOT)).append("://")
                        .append(host).append(':').append(port).append('/').append(database);
                String sep = "?";
                for (var e : new java.util.TreeMap<>(properties).entrySet()) {
                    url.append(sep).append(e.getKey()).append('=').append(e.getValue());
                    sep = "&";
                }
            }
            case SQLSERVER -> {
                url.append("jdbc:sqlserver://").append(host).append(':').append(port)
                        .append(";databaseName=").append(database);
                new java.util.TreeMap<>(properties).forEach((k, v) -> url.append(';').append(k).append('=').append(v));
            }
            case ORACLE -> url.append("jdbc:oracle:thin:@//").append(host).append(':').append(port).append('/').append(database);
        }
        return url.toString();
    }
}
