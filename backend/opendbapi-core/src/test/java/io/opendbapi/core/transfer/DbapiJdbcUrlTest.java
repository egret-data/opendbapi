package io.opendbapi.core.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.core.datasource.JdbcConfig;
import io.opendbapi.core.datasource.JdbcDialect;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DBAPI datasource URLs become structured settings (spec §15.4). */
class DbapiJdbcUrlTest {

    @Test
    void trinoCatalogAndSchemaBecomeCatalogDotSchema() {
        // Act
        JdbcConfig cfg = DbapiImporter.parseJdbcUrl("jdbc:trino://trino.corp:8443/hive/sales", "etl", new ArrayList<>());

        // Assert
        assertThat(cfg.dialect()).isEqualTo(JdbcDialect.TRINO);
        assertThat(cfg.host()).isEqualTo("trino.corp");
        assertThat(cfg.port()).isEqualTo(8443);
        assertThat(cfg.database()).isEqualTo("hive.sales");
    }

    @Test
    void clickHouseUrlsInBothSpellings() {
        // Act
        JdbcConfig full = DbapiImporter.parseJdbcUrl("jdbc:clickhouse://ch:8123/metrics", "u", new ArrayList<>());
        JdbcConfig shortForm = DbapiImporter.parseJdbcUrl("jdbc:ch://ch/metrics", "u", new ArrayList<>());

        // Assert
        assertThat(full.dialect()).isEqualTo(JdbcDialect.CLICKHOUSE);
        assertThat(full.database()).isEqualTo("metrics");
        assertThat(shortForm.dialect()).isEqualTo(JdbcDialect.CLICKHOUSE);
        assertThat(shortForm.port()).isNull();
    }

    @Test
    void unknownDatabasesAreNotedAndSkipped() {
        // Arrange
        List<String> notes = new ArrayList<>();

        // Act
        JdbcConfig cfg = DbapiImporter.parseJdbcUrl("jdbc:hive2://h:10000/default", "u", notes);

        // Assert
        assertThat(cfg).isNull();
        assertThat(notes).anyMatch(n -> n.startsWith("Database type not supported"));
    }
}
