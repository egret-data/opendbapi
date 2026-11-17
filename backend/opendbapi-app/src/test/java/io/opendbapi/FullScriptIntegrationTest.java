package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Full-mode Groovy scripts: db.*, any answer, data policies on every query (spec §7.4). */
class FullScriptIntegrationTest extends AppTestSupport {

    private static final String SCRIPT = """
            def user = db.one(sql"select 1 as id, 'Ann' as name, 'ann@example.com' as email where 1 = ${params.id}")
            if (!user) return notFound("No user ${params.id}")
            def orders = db.list(sql"select n as order_id from generate_series(1, 3) as n")
            return [user: user, orders: orders, total: db.value(sql"select count(*) from generate_series(1, 3)")]
            """;

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "fs-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Full " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private Map<String, Object> definition(String source) {
        return Map.of("params", List.of(Map.of("name", "id", "in", "query", "type", "bigint", "required", false)),
                "task", Map.of("type", "script", "datasourceId", dsId, "transaction", false, "language", "groovy", "mode", "full",
                        "source", source));
    }

    private String endpoint(String path, String source) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", definition(source))).expect(200);
        return id;
    }

    @Test
    void aFullScriptAnswersWithItsOwnShapeAndEveryQueryIsMasked() {
        // Arrange
        String id = endpoint("profile", SCRIPT);
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "endpointId", id, "column", "email",
                "mask", Map.of("type", "partial", "keepStart", 2, "keepEnd", 0))).expect(201);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        JsonNode found = client().get("/api/" + ws + "/profile?id=1").expect(200).json();
        var missing = client().get("/api/" + ws + "/profile?id=2");
        JsonNode preview = admin.post(ws("/endpoints/" + id + "/preview"), Map.of("method", "GET", "path", "profile",
                "definition", definition(SCRIPT), "params", Map.of("id", 1))).expect(200).json();

        // Assert
        assertThat(found.path("data").path("user").path("name").asText()).isEqualTo("Ann");
        assertThat(found.path("data").path("user").path("email").asText()).startsWith("an").doesNotContain("example");
        assertThat(found.path("data").path("orders")).hasSize(3);
        assertThat(found.path("data").path("total").asInt()).isEqualTo(3);
        assertThat(found.path("meta").path("version").asInt()).isEqualTo(1);
        missing.expectProblem(404, "not_found");
        assertThat(missing.body()).contains("No user 2");
        assertThat(preview.path("value").path("orders")).hasSize(3);
        assertThat(preview.path("executed")).hasSize(3);
    }

    @Test
    void theSandboxKeepsDbNarrowAndWritesNeedAWritableDatasource() {
        // Arrange
        String props = endpoint("props", "return db.connection");
        String reassign = endpoint("reassign", "db = null\nreturn 1");
        String write = endpoint("write", "return db.update(sql\"update pg_class set relname = relname where false\")");
        String wrongKind = endpoint("kind", "return db.list(sql\"delete from pg_class where false\")");
        String file = endpoint("file", "return 'file:///etc/passwd'.toURL().text");

        // Act
        JsonNode propsCheck = admin.post(ws("/endpoints/" + props + ":validate"), Map.of()).expect(200).json();
        JsonNode reassignCheck = admin.post(ws("/endpoints/" + reassign + ":validate"), Map.of()).expect(200).json();
        JsonNode fileCheck = admin.post(ws("/endpoints/" + file + ":validate"), Map.of()).expect(200).json();
        admin.post(ws("/endpoints/" + write + ":publish"), Map.of()).expect(200);
        admin.post(ws("/endpoints/" + wrongKind + ":publish"), Map.of()).expect(200);
        var writeOnReadOnly = client().get("/api/" + ws + "/write");
        var deleteThroughList = client().get("/api/" + ws + "/kind");

        // Assert
        assertThat(propsCheck.path("ok").asBoolean()).isFalse();
        assertThat(propsCheck.toString()).contains("db has no properties");
        assertThat(reassignCheck.toString()).contains("db and params cannot be assigned");
        assertThat(fileCheck.path("ok").asBoolean()).isFalse();
        writeOnReadOnly.expectProblem(403, "read_only_datasource");
        deleteThroughList.expectProblem(500, "script_failed");
    }
}
