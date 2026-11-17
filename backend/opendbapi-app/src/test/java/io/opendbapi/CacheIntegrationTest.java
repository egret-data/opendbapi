package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Result cache (spec §9.5): hits, keys, per-caller entries and invalidation. */
class CacheIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "ca-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Cache " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    /** clock_timestamp() changes on every execution, so equal values mean a cached answer. */
    private String endpoint(String path, String access, String sql, String scope) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "definition", Map.of("params", List.of(Map.of("name", "n", "in", "query", "type", "bigint", "required", false)),
                        "cache", Map.of("enabled", true, "ttlSeconds", 300, "scope", scope),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", sql)))))).expect(200);
        return id;
    }

    private JsonNode call(String path, String token) {
        return client().header("Authorization", token == null ? null : "Bearer " + token).get("/api/" + ws + "/" + path).expect(200).json();
    }

    private String token(String saId) {
        JsonNode created = admin.post(ws("/service-accounts/" + saId + "/clients"), Map.of()).expect(201).json();
        return client().form("/oauth2/token", Map.of("grant_type", "client_credentials",
                "client_id", created.path("client").path("clientId").asText(),
                "client_secret", created.path("clientSecret").asText())).expect(200).json().path("access_token").asText();
    }

    @Test
    void repeatedCallsAreServedFromCacheUntilCleared() {
        // Arrange
        String id = endpoint("clock", "public", "select clock_timestamp()::text as t, #{n}::int8 as n", "shared");
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        JsonNode first = call("clock?n=1", null);
        JsonNode second = call("clock?n=1", null);
        JsonNode otherParams = call("clock?n=2", null);
        admin.post(ws("/endpoints/" + id + "/cache:clear"), Map.of()).expect(204);
        JsonNode afterClear = call("clock?n=1", null);

        // Assert
        assertThat(first.path("meta").path("cached").asBoolean()).isFalse();
        assertThat(second.path("meta").path("cached").asBoolean()).isTrue();
        assertThat(second.path("data")).isEqualTo(first.path("data"));
        assertThat(otherParams.path("meta").path("cached").asBoolean()).isFalse();
        assertThat(afterClear.path("data").get(0).path("t")).isNotEqualTo(first.path("data").get(0).path("t"));
    }

    @Test
    void clientScopeKeepsEachCallersFilteredRowsApart() {
        // Arrange
        String id = endpoint("tenants", "private",
                "select t as tenant, clock_timestamp()::text as at from (values ('acme'), ('globex')) v(t)", "client");
        String acme = admin.post(ws("/service-accounts"), Map.of("name", "acme", "disabled", false, "attributes", Map.of("tenant", "acme")))
                .expect(201).json().path("summary").path("id").asText();
        String globex = admin.post(ws("/service-accounts"), Map.of("name", "globex", "disabled", false,
                "attributes", Map.of("tenant", "globex"))).expect(201).json().path("summary").path("id").asText();
        for (String sa : List.of(acme, globex)) {
            admin.put(ws("/service-accounts/" + sa + "/grants"), Map.of("resourceType", "endpoint", "resourceId", id)).expect(204);
        }
        admin.post(ws("/data-policies"), Map.of("kind", "row_filter", "endpointId", id, "column", "tenant", "attribute", "tenant"))
                .expect(201);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        String acmeToken = token(acme);
        String globexToken = token(globex);

        // Act
        call("tenants", acmeToken);
        JsonNode acmeCached = call("tenants", acmeToken);
        JsonNode globexFirst = call("tenants", globexToken);

        // Assert
        assertThat(acmeCached.path("meta").path("cached").asBoolean()).isTrue();
        assertThat(acmeCached.path("data").findValuesAsText("tenant")).containsExactly("acme");
        assertThat(globexFirst.path("meta").path("cached").asBoolean()).isFalse();
        assertThat(globexFirst.path("data").findValuesAsText("tenant")).containsExactly("globex");
    }

    @Test
    void policyChangesDropCachedAnswersAndSharedScopeIsRefusedWithPolicies() {
        // Arrange
        String id = endpoint("contacts", "public", "select 'ann@acme.test'::text as email, clock_timestamp()::text as at", "shared");
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        call("contacts", null);

        // Act: a mask added after the answer was cached
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "endpointId", id, "column", "email", "mask", Map.of("type", "email")))
                .expect(201);
        JsonNode after = call("contacts", null);
        JsonNode findings = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json();

        // Assert: never served unmasked, and not cached while shared + policies
        assertThat(after.path("data").get(0).path("email").asText()).isEqualTo("a***@acme.test");
        assertThat(call("contacts", null).path("meta").path("cached").asBoolean()).isFalse();
        assertThat(findings.findValuesAsText("message").toString()).contains("use cache scope client");
    }
}
