package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Migrations must also work on databases that already hold data: tables force row-level
 * security on their owner, so a backfill that forgets it silently updates nothing.
 */
class MigrationWithDataTest {

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "opendbapi_owner", "owner").schemas("opendbapi")
                .placeholders(Map.of("app_role", "opendbapi_app")).locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    @Test
    void laterMigrationsBackfillExistingRows() throws Exception {
        // Arrange
        var pg = AppTestSupport.POSTGRES;
        String db = "migr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement s = admin.createStatement()) {
            s.execute("create database " + db + " owner opendbapi_owner");
        }
        String url = pg.getJdbcUrl().replace("/opendbapi", "/" + db);
        flyway(url, "13").migrate();
        UUID endpoint;
        try (Connection c = DriverManager.getConnection(url, "opendbapi_owner", "owner")) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("set search_path to opendbapi");
                ResultSet ws = s.executeQuery("insert into workspace (slug, name) values ('acme', 'Acme') returning id");
                ws.next();
                String wsId = ws.getString(1);
                s.execute("select set_config('opendbapi.workspace_id', '" + wsId + "', true)");
                ResultSet ep = s.executeQuery("insert into endpoint (workspace_id, name, method, path) values ('" + wsId
                        + "', 'Orders', 'GET', 'orders') returning id");
                ep.next();
                endpoint = UUID.fromString(ep.getString(1));
            }
            c.commit();
        }

        // Act
        var result = flyway(url, null).migrate();

        // Assert
        assertThat(result.success).isTrue();
        try (Connection admin = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
             ResultSet rs = admin.createStatement().executeQuery("select lineage_id, major from opendbapi.endpoint")) {
            rs.next();
            assertThat(rs.getObject("lineage_id", UUID.class)).isEqualTo(endpoint);
            assertThat(rs.getInt("major")).isEqualTo(1);
        }
    }
}
