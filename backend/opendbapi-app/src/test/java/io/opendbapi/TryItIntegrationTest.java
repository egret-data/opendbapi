package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Try-it goes through the real serving pipeline (spec §11.2). */
class TryItIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "try-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Try " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private String endpoint(String path, String access, boolean publish) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "definition", Map.of(
                        "params", List.of(Map.of("name", "n", "in", "path", "type", "bigint", "required", true),
                                Map.of("name", "label", "in", "query", "type", "string", "required", false)),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template",
                                        "select #{n} * 2 as doubled, #{label} as label")))))).expect(200);
        if (publish) {
            admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        }
        return id;
    }

    private String serviceAccount(String name) {
        return admin.post(ws("/service-accounts"), Map.of("name", name, "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();
    }

    private ApiClient.Response tryIt(String id, Object saId, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceAccountId", saId);
        body.put("params", params);
        return admin.post(ws("/endpoints/" + id + "/try"), body);
    }

    @Test
    void publicEndpointIsCalledWithPathAndQueryParameters() {
        // Arrange
        String id = endpoint("double/{n}", "public", true);

        // Act
        JsonNode result = tryIt(id, null, Map.of("n", 21, "label", "a b")).expect(200).json();

        // Assert
        assertThat(result.path("status").asInt()).isEqualTo(200);
        assertThat(result.path("body").path("data").get(0).path("doubled").asInt()).isEqualTo(42);
        assertThat(result.path("body").path("data").get(0).path("label").asText()).isEqualTo("a b");
        assertThat(result.path("url").asText()).endsWith("/api/" + ws + "/double/21?label=a%20b");
        assertThat(result.path("curl").asText()).doesNotContain("$TOKEN");
    }

    @Test
    void privateEndpointWithoutGrantGetsTheReal403ThenWorksOnceGranted() {
        // Arrange
        String id = endpoint("secret/{n}", "private", true);
        String sa = serviceAccount("tester");

        // Act
        JsonNode refused = tryIt(id, sa, Map.of("n", 1)).expect(200).json();
        admin.put(ws("/service-accounts/" + sa + "/grants"), Map.of("resourceType", "endpoint", "resourceId", id)).expect(204);
        JsonNode allowed = tryIt(id, sa, Map.of("n", 2)).expect(200).json();

        // Assert
        assertThat(refused.path("status").asInt()).isEqualTo(403);
        assertThat(refused.path("body").path("code").asText()).isEqualTo("not_granted");
        assertThat(allowed.path("status").asInt()).isEqualTo(200);
        assertThat(allowed.path("serviceAccountName").asText()).isEqualTo("tester");
        assertThat(allowed.path("curl").asText()).contains("Authorization: Bearer $TOKEN");
        assertThat(allowed.toString()).doesNotContain("eyJ"); // no JWT reaches the browser
        assertThat(admin.get(ws("/audit?action=endpoint.try")).json().findValuesAsText("action")).contains("endpoint.try");
    }

    @Test
    void privateEndpointNeedsAServiceAccountAndUnpublishedOnesCannotBeTried() {
        // Arrange
        String priv = endpoint("p/{n}", "private", true);
        String draft = endpoint("d/{n}", "public", false);

        // Act + Assert
        tryIt(priv, null, Map.of("n", 1)).expectProblem(400, "service_account_required");
        tryIt(draft, null, Map.of("n", 1)).expectProblem(409, "not_published");
    }

    @Test
    void forgedRelayHeaderDoesNotMakeATryItCall() {
        // Arrange
        String id = endpoint("x/{n}", "public", true);
        ApiClient outsider = client().header("X-OpenDBAPI-Try-It", "guess " + UUID.randomUUID() + " 10.0.0.1");

        // Act + Assert: served as an ordinary call (header ignored), nothing breaks
        outsider.get("/api/" + ws + "/x/3").expect(200);
    }
}
