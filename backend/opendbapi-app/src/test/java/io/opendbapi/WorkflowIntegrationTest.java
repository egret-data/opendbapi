package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Workflow tasks: a graph of SQL and script nodes, independent ones in parallel (spec §6.1). */
class WorkflowIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Workflow " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private static Map<String, Object> node(String id, String kind, String text, List<String> dependsOn, String when, String result) {
        Map<String, Object> n = new HashMap<>(Map.of("id", id, "kind", kind, "dependsOn", dependsOn, "result", result));
        n.put("sql".equals(kind) ? "template" : "source", text);
        if (when != null) {
            n.put("when", when);
        }
        return n;
    }

    private String endpoint(String path, List<Map<String, Object>> nodes, String output) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> task = new HashMap<>(Map.of("type", "workflow", "datasourceId", dsId, "transaction", false, "nodes", nodes));
        if (output != null) {
            task.put("output", output);
        }
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", Map.of("params", List.of(Map.of("name", "id", "in", "query", "type", "bigint", "required", false)),
                        "task", task))).expect(200);
        return id;
    }

    @Test
    void nodesFeedEachOtherSkipOnConditionsAndAreMasked() {
        // Arrange
        String id = endpoint("profile", List.of(
                node("user", "sql", "select 1 as id, 'Ann' as name, 'ann@example.com' as email where 1 = #{id}", List.of(), null, "one"),
                node("stats", "sql", "select count(*) from generate_series(1, 5)", List.of(), null, "value"),
                node("orders", "sql", "select n as order_id, #{user.id} as user_id from generate_series(1, 3) as n", List.of("user"),
                        "user != null", "list"),
                node("shape", "script", "return [user: params.user, orders: params.orders, stats: params.stats]",
                        List.of("user", "orders", "stats"), null, "list")), "shape");
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "endpointId", id, "column", "email",
                "mask", Map.of("type", "nullify"))).expect(201);

        // Act
        JsonNode checked = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json();
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        JsonNode found = client().get("/api/" + ws + "/profile?id=1").expect(200).json().path("data");
        JsonNode missing = client().get("/api/" + ws + "/profile?id=2").expect(200).json().path("data");

        // Assert
        assertThat(checked.path("ok").asBoolean()).as(checked.toString()).isTrue();
        assertThat(checked.toString()).contains("Runs in 3 step(s): user + stats → orders → shape.");
        assertThat(found.path("user").path("name").asText()).isEqualTo("Ann");
        assertThat(found.path("user").path("email").isNull()).isTrue();
        assertThat(found.path("orders")).hasSize(3);
        assertThat(found.path("orders").get(0).path("user_id").asInt()).isEqualTo(1);
        assertThat(found.path("stats").asInt()).isEqualTo(5);
        assertThat(missing.path("user").isNull()).isTrue();
        assertThat(missing.path("orders").isNull()).isTrue();
        assertThat(missing.path("stats").asInt()).isEqualTo(5);
    }

    @Test
    void independentNodesRunInParallel() {
        // Arrange
        String id = endpoint("slow", List.of(
                node("a", "sql", "select 1 as a from pg_sleep(1)", List.of(), null, "value"),
                node("b", "sql", "select 2 as b from pg_sleep(1)", List.of(), null, "value"),
                node("both", "script", "return [a: params.a, b: params.b]", List.of("a", "b"), null, "list")), null);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        long start = System.nanoTime();
        JsonNode data = client().get("/api/" + ws + "/slow").expect(200).json().path("data");
        long ms = (System.nanoTime() - start) / 1_000_000;

        // Assert
        assertThat(data.path("a").asInt()).isEqualTo(1);
        assertThat(data.path("b").asInt()).isEqualTo(2);
        assertThat(ms).isLessThan(1900);
    }

    @Test
    void scriptNodesNeedTheScriptAuthorRole() {
        // Arrange
        String name = "author-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!", "systemAdmin", false))
                .expect(201).json().path("id").asText();
        admin.post(ws("/members"), Map.of("userId", userId, "role", "author")).expect(201);
        ApiClient author = client();
        author.login(name, name + "-password!").expect(204);
        String id = endpoint("guarded", List.of(node("a", "sql", "select 1", List.of(), null, "value")), null);
        Map<String, Object> sqlOnly = Map.of("type", "workflow", "datasourceId", dsId, "transaction", false,
                "nodes", List.of(node("a", "sql", "select 2", List.of(), null, "value")));
        Map<String, Object> withScript = Map.of("type", "workflow", "datasourceId", dsId, "transaction", false,
                "nodes", List.of(node("a", "sql", "select 2", List.of(), null, "value"), node("b", "script", "return 1", List.of("a"), null, "list")));

        // Act
        var plain = author.patch(ws("/endpoints/" + id), Map.of("name", "guarded", "method", "GET", "path", "guarded", "access", "public",
                "definition", Map.of("params", List.of(), "task", sqlOnly)));
        var scripted = author.patch(ws("/endpoints/" + id), Map.of("name", "guarded", "method", "GET", "path", "guarded", "access", "public",
                "definition", Map.of("params", List.of(), "task", withScript)));

        // Assert
        plain.expect(200);
        scripted.expect(403);
    }

    @Test
    void brokenGraphsAreReported() {
        // Arrange
        String cycle = endpoint("cycle", List.of(node("a", "sql", "select 1", List.of("b"), null, "value"),
                node("b", "sql", "select 2", List.of("a"), null, "value")), null);
        String unknown = endpoint("unknown", List.of(node("a", "sql", "select 1", List.of("ghost"), null, "value")), null);
        String writeAsList = endpoint("kind", List.of(node("a", "sql", "delete from pg_class where false", List.of(), null, "list")), null);

        // Act
        String cycleCheck = admin.post(ws("/endpoints/" + cycle + ":validate"), Map.of()).expect(200).body();
        String unknownCheck = admin.post(ws("/endpoints/" + unknown + ":validate"), Map.of()).expect(200).body();
        String kindCheck = admin.post(ws("/endpoints/" + writeAsList + ":validate"), Map.of()).expect(200).body();

        // Assert
        assertThat(cycleCheck).contains("depend on each other in a cycle");
        assertThat(unknownCheck).contains("depends on 'ghost', which does not exist");
        assertThat(kindCheck).contains("changes data; set its result to update").contains("write_in_get");
    }
}
