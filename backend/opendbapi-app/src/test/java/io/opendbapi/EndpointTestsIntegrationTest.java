package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Saved test cases of an endpoint, run against the draft, and the publish gate (spec §11.3). */
@TestPropertySource(properties = "opendbapi.publish.require-tests-pass=true")
class EndpointTestsIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;
    private String endpointId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "et-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Tests " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        endpointId = admin.post(ws("/endpoints"), Map.of("name", "series", "method", "GET", "path", "series", "access", "private",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        saveDraft("select g as n from generate_series(1, #{upto}) g order by g");
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private void saveDraft(String sql) {
        admin.patch(ws("/endpoints/" + endpointId), Map.of("name", "series", "method", "GET", "path", "series", "access", "private",
                "definition", Map.of("params", List.of(Map.of("name", "upto", "in", "query", "type", "bigint", "required", true)),
                        "rules", List.of(Map.of("test", "upto <= 100", "message", "upto is at most 100.")),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", sql)))))).expect(200);
    }

    private String addTest(Map<String, Object> test) {
        return admin.post(ws("/endpoints/" + endpointId + "/tests"), test).expect(201).json().path("id").asText();
    }

    private JsonNode run() {
        return admin.post(ws("/endpoints/" + endpointId + "/tests:run"), Map.of()).expect(200).json();
    }

    @Test
    void runsTestsAgainstTheDraftAndReportsWhatFailed() {
        // Arrange
        addTest(Map.of("name", "three rows", "params", Map.of("upto", 3), "expect", Map.of("status", 200,
                "rowCount", Map.of("min", 3, "max", 3),
                "jsonPath", List.of(Map.of("path", "$.data[2].n", "equals", 3), Map.of("path", "$.data[3]", "exists", false)))));
        addTest(Map.of("name", "rule refuses", "params", Map.of("upto", 500), "expect", Map.of("status", 400,
                "jsonPath", List.of(Map.of("path", "$.code", "equals", "rule_failed")))));
        addTest(Map.of("name", "wrong on purpose", "params", Map.of("upto", 2), "expect", Map.of("rowCount", Map.of("min", 5))));

        // Act
        JsonNode summary = run();
        JsonNode listed = admin.get(ws("/endpoints/" + endpointId + "/tests")).expect(200).json();

        // Assert
        assertThat(summary.path("passed").asInt()).isEqualTo(2);
        assertThat(summary.path("failed").asInt()).isEqualTo(1);
        JsonNode wrong = summary.path("results").findParents("name").stream()
                .filter(r -> r.path("name").asText().equals("wrong on purpose")).findFirst().orElseThrow();
        assertThat(wrong.path("failures").get(0).asText()).isEqualTo("Expected at least 5 rows, got 2.");
        assertThat(listed.findValuesAsText("passed")).hasSize(3);
    }

    @Test
    void aTestCanCallAsAServiceAccount() {
        // Arrange
        String saId = admin.post(ws("/service-accounts"), Map.of("name", "reporting", "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();
        String testId = addTest(Map.of("name", "needs a grant", "serviceAccountId", saId, "params", Map.of("upto", 1),
                "expect", Map.of("status", 200)));

        // Act
        JsonNode before = run();
        admin.put(ws("/service-accounts/" + saId + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId)).expect(204);
        JsonNode after = admin.post(ws("/endpoints/" + endpointId + "/tests:run?testId=" + testId), Map.of()).expect(200).json();

        // Assert
        assertThat(before.path("results").get(0).path("status").asInt()).isEqualTo(403);
        assertThat(after.path("results").get(0).path("passed").asBoolean()).isTrue();
    }

    @Test
    void publishNeedsPassingTestsAndSchemaChecksCompareWithLive() {
        // Arrange
        String failing = addTest(Map.of("name", "too strict", "params", Map.of("upto", 1), "expect", Map.of("rowCount", Map.of("min", 9))));
        addTest(Map.of("name", "same columns", "params", Map.of("upto", 1), "expect", Map.of("schemaMatchesLive", true)));

        // Act + Assert: blocked by the failing test (and the schema check, with nothing live yet)
        admin.post(ws("/endpoints/" + endpointId + ":publish"), Map.of()).expectProblem(409, "tests_failed");
        admin.delete(ws("/endpoints/" + endpointId + "/tests/" + failing)).expect(204);
        admin.post(ws("/endpoints/" + endpointId + ":publish"), Map.of()).expectProblem(409, "tests_failed");

        // Act + Assert: once live, the schema check passes until the draft changes its columns
        admin.patch(ws("/endpoints/" + endpointId + "/tests/" + listId("same columns")), Map.of("name", "same columns",
                "params", Map.of("upto", 1), "expect", Map.of("status", 200))).expect(200);
        admin.post(ws("/endpoints/" + endpointId + ":publish"), Map.of()).expect(200);
        admin.patch(ws("/endpoints/" + endpointId + "/tests/" + listId("same columns")), Map.of("name", "same columns",
                "params", Map.of("upto", 1), "expect", Map.of("schemaMatchesLive", true))).expect(200);
        assertThat(run().path("failed").asInt()).isZero();
        saveDraft("select g as n, g * 2 as twice from generate_series(1, #{upto}) g");
        JsonNode changed = run();
        assertThat(changed.path("failed").asInt()).isEqualTo(1);
        assertThat(changed.path("results").get(0).path("failures").get(0).asText()).startsWith("The result columns changed");
    }

    @Test
    void invalidTestsAreRefused() {
        // Assert
        admin.post(ws("/endpoints/" + endpointId + "/tests"), Map.of("name", "", "params", Map.of()))
                .expectProblem(400, "invalid_test");
        admin.post(ws("/endpoints/" + endpointId + "/tests"), Map.of("name", "bad path",
                "expect", Map.of("jsonPath", List.of(Map.of("path", "data[0]", "equals", 1))))).expectProblem(400, "invalid_test");
        addTest(Map.of("name", "once", "params", Map.of("upto", 1)));
        admin.post(ws("/endpoints/" + endpointId + "/tests"), Map.of("name", "once", "params", Map.of("upto", 1)))
                .expectProblem(409, "name_taken");
    }

    private String listId(String name) {
        for (JsonNode t : admin.get(ws("/endpoints/" + endpointId + "/tests")).json()) {
            if (t.path("name").asText().equals(name)) {
                return t.path("id").asText();
            }
        }
        throw new AssertionError("no test " + name);
    }
}
