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

/** GET endpoints never change data, not through scripts nor through the endpoints they call. */
class GetNoWritesIntegrationTest extends AppTestSupport {

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
    void writesFromGetAreRefusedAtValidationOrWhenTheyRun() throws Exception {
        // Arrange
        String table = "get_w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        sql("create table " + table + " (name text)");
        ApiClient admin = signedInAdmin();
        String ws = "gw-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/admin/api/ws/" + ws;
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Get " + ws)).expect(201);
        String dsId = admin.post(base + "/datasources", new HashMap<>(Map.of("name", "rw", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", false, "pool", Map.of()))).expect(201).json()
                .path("id").asText();
        java.util.function.BiFunction<String, String, String> endpoint = (method, path) -> admin.post(base + "/endpoints",
                Map.of("name", path, "method", method, "path", path, "access", "public", "datasourceId", dsId)).expect(201).json()
                .path("summary").path("id").asText();
        java.util.function.Consumer<Object[]> save = a -> admin.patch(base + "/endpoints/" + a[0], Map.of("name", a[2], "method", a[1],
                "path", a[2], "access", "public", "definition", Map.of("params", List.of(), "task", a[3]))).expect(200);

        String insert = endpoint.apply("POST", "insert");
        save.accept(new Object[] {insert, "POST", "insert", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                "statements", List.of(Map.of("id", "main", "template", "insert into " + table + " (name) values ('x')")))});
        admin.post(base + "/endpoints/" + insert + ":publish", Map.of()).expect(200);

        String script = endpoint.apply("GET", "script");
        save.accept(new Object[] {script, "GET", "script", Map.of("type", "script", "datasourceId", dsId, "transaction", false,
                "language", "groovy", "mode", "full", "source", "return db.update(sql\"delete from " + table + " where false\")")});
        Map<String, Object> callInsert = Map.of("type", "workflow", "datasourceId", dsId, "transaction", false,
                "nodes", List.of(Map.of("id", "add", "kind", "api", "endpointId", insert)));
        String viaGet = endpoint.apply("GET", "via-get");
        save.accept(new Object[] {viaGet, "GET", "via-get", callInsert});
        admin.post(base + "/endpoints/" + viaGet + ":publish", Map.of()).expect(200);
        String viaPost = endpoint.apply("POST", "via-post");
        save.accept(new Object[] {viaPost, "POST", "via-post", callInsert});
        admin.post(base + "/endpoints/" + viaPost + ":publish", Map.of()).expect(200);

        // Act
        String scriptCheck = admin.post(base + "/endpoints/" + script + ":validate", Map.of()).expect(200).body();
        var get = client().get("/api/" + ws + "/via-get");
        int afterGet = count(table);
        var post = client().post("/api/" + ws + "/via-post", Map.of());
        int afterPost = count(table);

        // Assert
        assertThat(scriptCheck).contains("write_in_get").contains("db.update");
        get.expectProblem(403, "write_in_get");
        assertThat(afterGet).isZero();
        post.expect(200);
        assertThat(afterPost).isEqualTo(1);
    }
}
