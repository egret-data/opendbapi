package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Generated OpenAPI documents and Swagger UI (spec §10). */
class ApiDocsIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "doc-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Docs " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private String endpoint(String path, String access, List<Map<String, Object>> params, String sql, boolean paginate) {
        String id = admin.post(ws("/endpoints"), Map.of("name", "Endpoint " + path, "method", "GET", "path", path, "access", access,
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> def = new HashMap<>(Map.of("params", params, "task", Map.of("type", "sql", "datasourceId", dsId,
                "transaction", false, "statements", List.of(Map.of("id", "main", "template", sql)))));
        if (paginate) {
            def.put("pagination", Map.of("enabled", true, "defaultSize", 10, "maxSize", 50, "count", true));
        }
        admin.patch(ws("/endpoints/" + id), Map.of("name", "Endpoint " + path, "description", "Docs for " + path, "method", "GET",
                "path", path, "access", access, "definition", def)).expect(200);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        return id;
    }

    @Test
    void anonymousDocumentListsPublicEndpointsWithTypedParametersAndColumns() {
        // Arrange
        Map<String, Object> id = new HashMap<>(Map.of("name", "id", "in", "path", "type", "bigint", "required", true));
        Map<String, Object> kind = new HashMap<>(Map.of("name", "kind", "in", "query", "type", "string", "required", false,
                "enumValues", List.of("a", "b")));
        endpoint("things/{id}", "public", List.of(id, kind),
                "select #{id}::int8 as id, #{kind}::text as name, now() as created, true as ok", true);
        endpoint("hidden", "private", List.of(), "select 1 as n", false);

        // Act
        JsonNode doc = client().get("/api/" + ws + "/openapi.json").expect(200).json();

        // Assert
        assertThat(doc.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(doc.path("paths").has("/hidden")).isFalse();
        JsonNode op = doc.path("paths").path("/things/{id}").path("get");
        assertThat(op.path("summary").asText()).isEqualTo("Endpoint things/{id}");
        assertThat(op.path("parameters").findValuesAsText("name")).containsExactly("id", "kind", "page", "size");
        assertThat(op.path("parameters").get(0).path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(op.path("parameters").get(1).path("schema").path("enum").toString()).isEqualTo("[\"a\",\"b\"]");
        JsonNode row = op.path("responses").path("200").path("content").path("application/json").path("schema")
                .path("properties").path("data").path("items").path("properties");
        assertThat(row.path("id").path("type").get(0).asText()).isEqualTo("integer");
        assertThat(row.path("created").path("format").asText()).isEqualTo("date-time");
        assertThat(row.path("ok").path("type").get(0).asText()).isEqualTo("boolean");
        assertThat(doc.path("components").path("securitySchemes").path("oauth2").path("flows").path("clientCredentials")
                .path("tokenUrl").asText()).endsWith("/oauth2/token");
    }

    @Test
    void tokenRevealsOnlyThePrivateEndpointsItsAccountMayCall() {
        // Arrange
        String granted = endpoint("mine", "private", List.of(), "select 1 as n", false);
        endpoint("theirs", "private", List.of(), "select 2 as n", false);
        String sa = admin.post(ws("/service-accounts"), Map.of("name", "reader", "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();
        JsonNode client = admin.post(ws("/service-accounts/" + sa + "/clients"), Map.of()).expect(201).json();
        admin.put(ws("/service-accounts/" + sa + "/grants"), Map.of("resourceType", "endpoint", "resourceId", granted)).expect(204);
        String basic = Base64.getEncoder().encodeToString((client.path("client").path("clientId").asText() + ":"
                + client.path("clientSecret").asText()).getBytes(StandardCharsets.UTF_8));
        String token = client().header("Authorization", "Basic " + basic)
                .form("/oauth2/token", Map.of("grant_type", "client_credentials")).expect(200).json().path("access_token").asText();

        // Act
        JsonNode doc = client().header("Authorization", "Bearer " + token).get("/api/" + ws + "/openapi.json").expect(200).json();

        // Assert
        assertThat(doc.path("paths").has("/mine")).isTrue();
        assertThat(doc.path("paths").has("/theirs")).isFalse();
        assertThat(doc.path("paths").path("/mine").path("get").path("security").get(0).has("oauth2")).isTrue();
    }

    @Test
    void membersDownloadTheWholeDocumentOrOneEndpoint() {
        // Arrange
        String id = endpoint("one", "private", List.of(), "select 1 as n", false);
        endpoint("two", "public", List.of(), "select 2 as n", false);

        // Act
        JsonNode all = admin.get(ws("/openapi.json")).expect(200).json();
        JsonNode one = admin.get(ws("/endpoints/" + id + "/openapi.json")).expect(200).json();

        // Assert
        assertThat(all.path("paths").has("/one")).isTrue();
        assertThat(all.path("paths").has("/two")).isTrue();
        assertThat(one.path("paths").size()).isEqualTo(1);
        assertThat(one.path("servers").get(0).path("url").asText()).endsWith("/api/" + ws);
    }

    @Test
    void swaggerUiIsServedFromTheJar() {
        // Act
        ApiClient.Response page = client().get("/api/" + ws + "/docs.html");
        ApiClient.Response bundle = client().get("/webjars/swagger-ui/5.17.14/swagger-ui-bundle.js");

        // Assert
        page.expect(200);
        assertThat(page.body()).contains("swagger-ui-bundle.js").contains("/api/" + ws + "/openapi.json");
        bundle.expect(200);
    }
}
