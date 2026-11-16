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
