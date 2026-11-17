package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Major versions side by side, deprecation and breaking-change warnings (spec §16). */
class MajorVersionIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "mv-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Majors " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private void define(String id, String name, String path, String sql) {
        admin.patch(ws("/endpoints/" + id), Map.of("name", name, "method", "GET", "path", path, "access", "public",
                "definition", Map.of("params", List.of(), "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                        "statements", List.of(Map.of("id", "main", "template", sql)))))).expect(200);
    }

    private HttpResponse<String> call(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/" + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aNewMajorRunsBesideTheOldOneWhichAnnouncesItsDeprecation() throws Exception {
        // Arrange
        String v1 = admin.post(ws("/endpoints"), Map.of("name", "Orders", "method", "GET", "path", "orders", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        define(v1, "Orders", "orders", "select 1 as id, 'a' as name");
        admin.post(ws("/endpoints/" + v1 + ":publish"), Map.of()).expect(200);

        // Act
        JsonNode major = admin.post(ws("/endpoints/" + v1 + ":new-major"), Map.of()).expect(201).json().path("summary");
        String v2 = major.path("id").asText();
        define(v2, major.path("name").asText(), major.path("path").asText(), "select 1 as id");
        admin.post(ws("/endpoints/" + v2 + ":publish"), Map.of()).expect(200);
        admin.post(ws("/endpoints/" + v1 + ":deprecate"), Map.of("sunset", "2027-06-30T00:00:00Z")).expect(200);
        HttpResponse<String> old = call("orders");
        HttpResponse<String> current = call("v2/orders");
        JsonNode lineage = admin.get(ws("/endpoints/" + v2 + "/lineage")).expect(200).json();
        JsonNode docs = ApiClient.JSON.readTree(call("openapi.json").body());
        admin.post(ws("/endpoints/" + v1 + ":undeprecate"), null).expect(200);
        HttpResponse<String> afterUndo = call("orders");

        // Assert
        assertThat(major.path("path").asText()).isEqualTo("v2/orders");
        assertThat(major.path("name").asText()).isEqualTo("Orders v2");
        assertThat(major.path("major").asInt()).isEqualTo(2);
        assertThat(major.path("state").asText()).isEqualTo("draft");
        assertThat(old.statusCode()).isEqualTo(200);
        assertThat(old.body()).contains("\"name\":\"a\"");
        assertThat(old.headers().firstValue("Deprecation")).hasValueSatisfying(v -> assertThat(v).startsWith("@"));
        assertThat(old.headers().firstValue("Sunset")).hasValue("Wed, 30 Jun 2027 00:00:00 GMT");
        assertThat(old.headers().firstValue("Link")).hasValue("</api/" + ws + "/v2/orders>; rel=\"successor-version\"");
        assertThat(current.body()).doesNotContain("\"name\"");
        assertThat(current.headers().firstValue("Deprecation")).isEmpty();
        assertThat(lineage).hasSize(2);
        assertThat(lineage.get(0).path("major").asInt()).isEqualTo(1);
        assertThat(lineage.get(0).path("deprecatedAt").isNull()).isFalse();
        assertThat(docs.path("paths").path("/orders").path("get").path("deprecated").asBoolean()).isTrue();
        assertThat(docs.path("paths").path("/v2/orders").path("get").has("deprecated")).isFalse();
        assertThat(afterUndo.headers().firstValue("Deprecation")).isEmpty();
    }

    @Test
    void droppingALiveColumnWarnsOfABreakingChange() {
        // Arrange
        String id = admin.post(ws("/endpoints"), Map.of("name", "Users", "method", "GET", "path", "v1/users", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        define(id, "Users", "v1/users", "select 1 as id, 'a' as name");
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        define(id, "Users", "v1/users", "select 1 as id");
        JsonNode findings = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json().path("findings");
        JsonNode next = admin.post(ws("/endpoints/" + id + ":new-major"), Map.of()).expect(201).json().path("summary");
        admin.post(ws("/endpoints/" + id + ":new-major"), Map.of("path", "v2/users")).expectProblem(409, "route_taken");

        // Assert
        assertThat(findings.toString()).contains("breaking_change").contains("drops column 'name'");
        assertThat(next.path("path").asText()).isEqualTo("v2/users");
    }
}
