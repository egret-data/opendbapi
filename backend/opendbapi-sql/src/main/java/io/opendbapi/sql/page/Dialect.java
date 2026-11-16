package io.opendbapi.sql.page;

import java.util.Locale;

/** How a database spells pagination (spec §9.3). */
public enum Dialect {
    POSTGRESQL(Style.LIMIT_OFFSET),
    MYSQL(Style.LIMIT_OFFSET),
    MARIADB(Style.LIMIT_OFFSET),
    SQLITE(Style.LIMIT_OFFSET),
    H2(Style.LIMIT_OFFSET),
    CLICKHOUSE(Style.LIMIT_OFFSET),
    SQLSERVER(Style.OFFSET_FETCH_ORDERED),
    ORACLE(Style.OFFSET_FETCH),
    DB2(Style.OFFSET_FETCH),
    /** Unknown: the widely supported {@code LIMIT … OFFSET …}. */
    OTHER(Style.LIMIT_OFFSET);

    enum Style {
        LIMIT_OFFSET,
        OFFSET_FETCH,
        /** SQL Server: OFFSET/FETCH is only legal after ORDER BY. */
        OFFSET_FETCH_ORDERED
    }

    final Style style;

    Dialect(Style style) {
        this.style = style;
    }

    public static Dialect fromJdbcUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:")) return POSTGRESQL;
        if (u.startsWith("jdbc:mysql:")) return MYSQL;
        if (u.startsWith("jdbc:mariadb:")) return MARIADB;
        if (u.startsWith("jdbc:sqlite:")) return SQLITE;
        if (u.startsWith("jdbc:h2:")) return H2;
        if (u.startsWith("jdbc:clickhouse:") || u.startsWith("jdbc:ch:")) return CLICKHOUSE;
        if (u.startsWith("jdbc:sqlserver:") || u.startsWith("jdbc:jtds:")) return SQLSERVER;
        if (u.startsWith("jdbc:oracle:")) return ORACLE;
        if (u.startsWith("jdbc:db2:")) return DB2;
        return OTHER;
    }
}
