package io.opendbapi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcConfigTest {

    private static JdbcConfig config(JdbcDialect dialect, String host, String database, Map<String, String> props) {
        return new JdbcConfig(dialect, host, null, database, "reader", props, null);
    }

    @Test
    void buildsAUrlPerDialectWithDefaultPorts() {
        // Act + Assert
        assertThat(config(JdbcDialect.POSTGRESQL, "db.corp.local", "sales", Map.of()).jdbcUrl())
                .isEqualTo("jdbc:postgresql://db.corp.local:5432/sales");
        assertThat(config(JdbcDialect.MYSQL, "10.0.0.5", "shop", Map.of()).jdbcUrl())
                .isEqualTo("jdbc:mysql://10.0.0.5:3306/shop");
        assertThat(config(JdbcDialect.MARIADB, "maria", "shop", Map.of()).jdbcUrl())
                .isEqualTo("jdbc:mariadb://maria:3306/shop");
        assertThat(config(JdbcDialect.SQLSERVER, "mssql", "Sales", Map.of("encrypt", "true")).jdbcUrl())
                .isEqualTo("jdbc:sqlserver://mssql:1433;databaseName=Sales;encrypt=true");
        assertThat(config(JdbcDialect.ORACLE, "ora", "ORCLPDB1.corp", Map.of()).jdbcUrl())
                .isEqualTo("jdbc:oracle:thin:@//ora:1521/ORCLPDB1.corp");
        assertThat(new JdbcConfig(JdbcDialect.POSTGRESQL, "[::1]", 6543, "x", "u", Map.of("sslmode", "require"), null).jdbcUrl())
                .isEqualTo("jdbc:postgresql://[::1]:6543/x?sslmode=require");
    }

    @Test
    void buildsUrlsForTheAnalyticsEngines() {
        // Act + Assert
        assertThat(config(JdbcDialect.TRINO, "trino", "hive", Map.of()).jdbcUrl()).isEqualTo("jdbc:trino://trino:8080/hive");
        assertThat(config(JdbcDialect.TRINO, "trino", "hive.sales", Map.of("SSL", "true")).jdbcUrl())
                .isEqualTo("jdbc:trino://trino:8080/hive/sales?SSL=true");
        assertThat(config(JdbcDialect.STARROCKS, "sr", "shop", Map.of()).jdbcUrl()).isEqualTo("jdbc:mysql://sr:9030/shop");
        assertThat(config(JdbcDialect.STARROCKS, "sr", "hive_catalog.shop", Map.of()).jdbcUrl())
                .isEqualTo("jdbc:mysql://sr:9030/hive_catalog.shop");
        assertThat(config(JdbcDialect.DORIS, "doris", "shop", Map.of("connectTimeout", "5000")).jdbcUrl())
                .isEqualTo("jdbc:mysql://doris:9030/shop?connectTimeout=5000");
        assertThat(config(JdbcDialect.CLICKHOUSE, "ch", "default", Map.of("ssl", "true")).jdbcUrl())
                .isEqualTo("jdbc:clickhouse://ch:8123/default?ssl=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b.c", "x.y.z.w"})
    void catalogQualifiedNamesHaveOneDotAtMost(String database) {
        // Act + Assert
        for (JdbcDialect d : new JdbcDialect[] {JdbcDialect.TRINO, JdbcDialect.STARROCKS, JdbcDialect.DORIS}) {
            assertThatThrownBy(() -> config(d, "h", database, Map.of()).validate()).isInstanceOf(ApiProblem.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSLTrustStorePath", "SSLKeyStorePath", "httpProxy", "socksProxy", "externalAuthentication",
            "KerberosKeytabPath", "accessToken", "extraCredentials"})
    void trinoFileProxyAndCredentialPropertiesAreRefused(String property) {
        // Act + Assert
        assertThatThrownBy(() -> config(JdbcDialect.TRINO, "h", "hive", Map.of(property, "x")).validate())
                .isInstanceOf(ApiProblem.class).hasMessageContaining("not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clickhouse_setting_readonly", "sslrootcert", "trust_store", "proxy_host", "http_header_X", "custom_settings"})
    void clickHouseSettingsFilesAndProxiesAreRefused(String property) {
        // Act + Assert: a server setting could lift readonly
        assertThatThrownBy(() -> config(JdbcDialect.CLICKHOUSE, "h", "default", Map.of(property, "0")).validate())
                .isInstanceOf(ApiProblem.class).hasMessageContaining("not allowed");
    }

    @Test
    void starRocksAndDorisKeepTheMySqlSafetyProperties() {
        // Act + Assert
        assertThat(JdbcDialect.STARROCKS.forcedProperties()).isEqualTo(JdbcDialect.MYSQL.forcedProperties());
        assertThat(JdbcDialect.DORIS.forcedProperties()).isEqualTo(JdbcDialect.MYSQL.forcedProperties());
        assertThatThrownBy(() -> config(JdbcDialect.DORIS, "h", "d", Map.of("autoDeserialize", "true")).validate())
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void onlyTrueTransactionsCountAsTransactional() {
        // Act + Assert
        assertThat(JdbcDialect.TRINO.transactional()).isTrue();
        assertThat(JdbcDialect.STARROCKS.transactional()).isFalse();
        assertThat(JdbcDialect.DORIS.transactional()).isFalse();
        assertThat(JdbcDialect.CLICKHOUSE.transactional()).isFalse();
        assertThat(JdbcDialect.POSTGRESQL.transactional()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"socketFactory", "sslfactory", "authenticationPluginClassName", "loggerFile", "sslpasswordcallback"})
    void postgresClassLoadingPropertiesAreRefused(String property) {
        // Act + Assert (CVE-2022-21724 class: driver instantiates a class named in the URL)
        assertThatThrownBy(() -> config(JdbcDialect.POSTGRESQL, "h", "d", Map.of(property, "x")).validate())
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining(property)
                .hasMessageContaining("not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"autoDeserialize", "allowLoadLocalInfile", "allowUrlInLocalInfile", "queryInterceptors"})
    void mysqlDangerousPropertiesAreRefused(String property) {
        // Act + Assert
        assertThatThrownBy(() -> config(JdbcDialect.MYSQL, "h", "d", Map.of(property, "true")).validate())
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void safeDriverPropertiesAreAlwaysForced() {
        // Act + Assert
        assertThat(JdbcDialect.MYSQL.forcedProperties())
                .containsEntry("allowLoadLocalInfile", "false")
                .containsEntry("autoDeserialize", "false");
        assertThat(JdbcDialect.MARIADB.forcedProperties()).containsEntry("allowLocalInfile", "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"db/../x", "db?x=1", "db;x=1", "db&x", "a b", "", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void databaseNamesCannotSmuggleUrlSyntax(String database) {
        // Act + Assert
        assertThatThrownBy(() -> config(JdbcDialect.POSTGRESQL, "h", database, Map.of()).validate())
                .isInstanceOf(ApiProblem.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"h/x", "h?x", "h;x", "user@h", "h:5432", "", "-h"})
    void hostsCannotSmuggleUrlSyntax(String host) {
        // Act + Assert
        assertThatThrownBy(() -> config(JdbcDialect.POSTGRESQL, host, "d", Map.of()).validate())
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void propertyValuesCannotStartAnotherProperty() {
        // Act + Assert
        assertThatThrownBy(() -> config(JdbcDialect.SQLSERVER, "h", "d", Map.of("encrypt", "true;integratedSecurity=true")).validate())
                .isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> config(JdbcDialect.POSTGRESQL, "h", "d", Map.of("sslmode", "require&socketFactory=x")).validate())
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void poolSettingsHaveDefaultsAndCaps() {
        // Act
        JdbcConfig.Pool defaults = config(JdbcDialect.POSTGRESQL, "h", "d", Map.of()).pool();
        JdbcConfig huge = new JdbcConfig(JdbcDialect.POSTGRESQL, "h", null, "d", "u", Map.of(),
                new JdbcConfig.Pool(500, 0, 999_999));

        // Assert
        assertThat(defaults.maximumPoolSize()).isEqualTo(10);
        assertThat(defaults.connectionTimeoutMs()).isEqualTo(10_000);
        assertThatThrownBy(huge::validate).isInstanceOf(ApiProblem.class).hasMessageContaining("maximumPoolSize");
    }
}
