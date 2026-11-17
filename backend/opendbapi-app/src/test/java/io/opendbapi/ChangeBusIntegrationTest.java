package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.core.datasource.DataSourceRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Changes made by another instance reach this one through PostgreSQL LISTEN/NOTIFY. */
class ChangeBusIntegrationTest extends AppTestSupport {

    @Autowired
    DataSourceRegistry pools;

    private static boolean within(long millis, BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    void anotherInstanceClosingADatasourcePoolClosesItHereToo() throws Exception {
        // Arrange
        ApiClient admin = signedInAdmin();
        String ws = "cb-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Bus " + ws)).expect(201);
        String dsId = admin.post("/admin/api/ws/" + ws + "/datasources", Map.of("name", "main", "dialect", "POSTGRESQL",
                "host", POSTGRES.getHost(), "port", POSTGRES.getMappedPort(5432), "database", "opendbapi",
                "username", POSTGRES.getUsername(), "password", POSTGRES.getPassword(), "properties", Map.of(),
                "readOnly", true, "pool", Map.of())).expect(201).json().path("id").asText();
        admin.get("/admin/api/ws/" + ws + "/datasources/" + dsId + "/schemas").expect(200);
        boolean pooledBefore = pools.isPooled(UUID.fromString(dsId));

        // Act: what another pod sends when its admin API changes the datasource.
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var ps = c.prepareStatement("select pg_notify('opendbapi_changes', ?)")) {
            ps.setString(1, "other-pod datasource " + dsId);
            ps.execute();
        }

        // Assert
        assertThat(pooledBefore).isTrue();
        assertThat(within(5_000, () -> !pools.isPooled(UUID.fromString(dsId)))).isTrue();
    }
}
