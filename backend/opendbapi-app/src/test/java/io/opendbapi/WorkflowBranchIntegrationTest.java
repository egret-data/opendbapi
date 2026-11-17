package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Workflow api, decision and end nodes, as in an upsert orchestration (spec §6.1). */
class WorkflowBranchIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "wb-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Branches " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private String create(String path) {
        return admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public", "datasourceId", dsId))
                .expect(201).json().path("summary").path("id").asText();
    }

    private void save(String id, String path, Map<String, Object> task) {
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", Map.of("params", List.of(Map.of("name", "name", "in", "query", "type", "string", "required", false)),
                        "task", task))).expect(200);
    }

    private String sqlEndpoint(String path, String sql) {
        String id = create(path);
        save(id, path, Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                "statements", List.of(Map.of("id", "main", "template", sql))));
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        return id;
    }

    private static Map<String, Object> node(String id, String kind, Map<String, Object> fields, String... dependsOn) {
        Map<String, Object> n = new HashMap<>(fields);
        n.put("id", id);
        n.put("kind", kind);
        n.put("dependsOn", List.of(dependsOn));
        return n;
    }

    private Map<String, Object> workflow(List<Map<String, Object>> nodes) {
        return Map.of("type", "workflow", "datasourceId", dsId, "transaction", false, "nodes", nodes);
    }

    @Test
    void aDecisionRoutesToOneBranchAndItsEndNodeAnswers() {
        // Arrange
        String count = sqlEndpoint("count", "select count(*) as num from (values ('Ann')) t(n) where n = #{name}");
        String add = sqlEndpoint("add", "select 'added ' || #{name} as msg");
        String update = sqlEndpoint("update", "select 'updated ' || #{name} as msg");
        String id = create("upsert");
        save(id, "upsert", workflow(List.of(
                node("count", "api", Map.of("endpointId", count, "params", Map.of("name", "name"))),
                node("isNew", "decision", Map.of("condition", "count[0].num == 0"), "count"),
                node("add", "api", Map.of("endpointId", add, "params", Map.of("name", "name")), "isNew:yes"),
                node("update", "api", Map.of("endpointId", update, "params", Map.of("name", "name")), "isNew:no"),
                node("endAdd", "end", Map.of("answer", "add"), "add"),
                node("endUpdate", "end", Map.of("answer", "update"), "update"))));

        // Act
        JsonNode checked = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json();
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        JsonNode fresh = client().get("/api/" + ws + "/upsert?name=Bob").expect(200).json().path("data");
        JsonNode existing = client().get("/api/" + ws + "/upsert?name=Ann").expect(200).json().path("data");

        // Assert
        assertThat(checked.path("ok").asBoolean()).as(checked.toString()).isTrue();
        assertThat(checked.toString()).contains("Runs in 4 step(s): count → isNew → add + update → endAdd + endUpdate.");
        assertThat(fresh.get(0).path("msg").asText()).isEqualTo("added Bob");
        assertThat(existing.get(0).path("msg").asText()).isEqualTo("updated Ann");
    }

    @Test
    void missingTargetsHalfUsedDecisionsAndNodesAfterAnEndAreReported() {
        // Arrange
        String a = create("a");
        save(a, "a", workflow(List.of(
                node("call", "api", Map.of("endpointId", UUID.randomUUID().toString())),
                node("check", "decision", Map.of("condition", "call != null"), "call"),
                node("yes", "end", Map.of("answer", "call"), "check:yes"))));
        String b = create("b");
        save(b, "b", workflow(List.of(node("stop", "end", Map.of("answer", "1")), node("after", "end", Map.of("answer", "2"), "stop"))));

        // Act
        String aCheck = admin.post(ws("/endpoints/" + a + ":validate"), Map.of()).expect(200).body();
        String bCheck = admin.post(ws("/endpoints/" + b + ":validate"), Map.of()).expect(200).body();

        // Assert
        assertThat(aCheck).contains("calls an endpoint that does not exist").contains("Decision 'check' has nothing on its no branch");
        assertThat(bCheck).contains("comes after the end node 'stop'");
    }

    @Test
    void endpointsCallingEachOtherStop() {
        // Arrange
        String a = create("ping");
        String b = create("pong");
        save(a, "ping", workflow(List.of(node("other", "api", Map.of("endpointId", b)))));
        save(b, "pong", workflow(List.of(node("other", "api", Map.of("endpointId", a)))));
        admin.post(ws("/endpoints/" + a + ":publish"), Map.of()).expect(200);
        admin.post(ws("/endpoints/" + b + ":publish"), Map.of()).expect(200);

        // Act
        var loop = client().get("/api/" + ws + "/ping");

        // Assert
        loop.expectProblem(500, "workflow_failed");
        assertThat(loop.body()).contains("more than 3 deep");
    }
}
