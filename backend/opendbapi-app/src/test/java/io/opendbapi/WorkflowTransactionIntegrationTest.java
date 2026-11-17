package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An api node joins its workflow's transaction when both use one datasource (spec §6.1). */
class WorkflowTransactionIntegrationTest extends AppTestSupport {

    private static void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            c.createStatement().execute(statement);
        }
    }

    private static int count(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet rs = c.createStatement().executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void aFailingStepRollsBackTheCalledEndpointsWriteOnlyInsideATransaction() throws Exception {
        // Arrange
        String table = "wf_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        sql("create table " + table + " (name text)");
        ApiClient admin = signedInAdmin();
        String ws = "wt-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/admin/api/ws/" + ws;
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Tx " + ws)).expect(201);
        String dsId = admin.post(base + "/datasources", new HashMap<>(Map.of("name", "rw", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", false, "pool", Map.of()))).expect(201).json()
                .path("id").asText();
        List<Map<String, Object>> params = List.of(Map.of("name", "name", "in", "query", "type", "string", "required", false));
        String insert = admin.post(base + "/endpoints", Map.of("name", "insert", "method", "POST", "path", "insert", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(base + "/endpoints/" + insert, Map.of("name", "insert", "method", "POST", "path", "insert", "access", "public",
                "definition", Map.of("params", params, "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                        "statements", List.of(Map.of("id", "main", "template", "insert into " + table + " (name) values (#{name})")))))).expect(200);
        admin.post(base + "/endpoints/" + insert + ":publish", Map.of()).expect(200);
        for (boolean transaction : new boolean[] {true, false}) {
            String path = transaction ? "together" : "apart";
            String id = admin.post(base + "/endpoints", Map.of("name", path, "method", "POST", "path", path, "access", "public",
                    "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
            admin.patch(base + "/endpoints/" + id, Map.of("name", path, "method", "POST", "path", path, "access", "public",
                    "definition", Map.of("params", params, "task", Map.of("type", "workflow", "datasourceId", dsId, "transaction", transaction,
                            "nodes", List.of(
                                    Map.of("id", "add", "kind", "api", "endpointId", insert, "params", Map.of("name", "name")),
                                    Map.of("id", "boom", "kind", "sql", "template", "select 1 / 0", "result", "value",
                                            "dependsOn", List.of("add"))))))).expect(200);
            admin.post(base + "/endpoints/" + id + ":publish", Map.of()).expect(200);
        }

        // Act
        var together = client().post("/api/" + ws + "/together?name=ann", Map.of());
        int afterTogether = count(table);
        var apart = client().post("/api/" + ws + "/apart?name=bob", Map.of());
        int afterApart = count(table);

        // Assert
        assertThat(together.status()).isEqualTo(500);
        assertThat(afterTogether).isZero();
        assertThat(apart.status()).isEqualTo(500);
        assertThat(afterApart).isEqualTo(1);
    }
}
