package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Workspace firewall and rate limits on the serving path (spec §14). */
class ServingPolicyIntegrationTest extends AppTestSupport {

    private static final List<String> LOOPBACK = List.of("127.0.0.1", "::1");

    private ApiClient admin;
    private String ws;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "sp-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Policy " + ws)).expect(201);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private void publicEndpoint(String path, Integer ratePerMinute) {
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta-" + path, "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> limits = new HashMap<>(Map.of("timeoutSeconds", 30, "maxRows", 100));
        limits.put("ratePerMinute", ratePerMinute);
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", Map.of("params", List.of(), "limits", limits, "task", Map.of("type", "sql", "datasourceId", dsId,
                        "transaction", false, "statements", List.of(Map.of("id", "main", "template", "select 1 as ok")))))).expect(200);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
    }

    private ApiClient.Response call(String path) {
        return client().get("/api/" + ws + "/" + path);
    }

    private void policy(String mode, List<String> entries, Integer perIp) {
        Map<String, Object> rate = new HashMap<>();
        rate.put("perIpPerMinute", perIp);
        rate.put("perClientPerMinute", null);
        admin.put(ws("/serving-policy"), Map.of("firewall", Map.of("mode", mode, "entries", entries), "rateLimits", rate)).expect(200);
    }

    @Test
    void denyListBlocksAndAllowListAdmits() {
        // Arrange
        publicEndpoint("fw", null);

        // Act + Assert
        call("fw").expect(200);
        policy("deny", LOOPBACK, null);
        call("fw").expectProblem(403, "ip_blocked");
        policy("allow", LOOPBACK, null);
        call("fw").expect(200);
        policy("allow", List.of("198.51.100.0/24"), null);
        call("fw").expectProblem(403, "ip_blocked");
        assertThat(admin.get(ws("/serving-policy")).json().path("firewall").path("entries").get(0).asText()).isEqualTo("198.51.100.0/24");
    }

    @Test
    void endpointRateLimitAnswers429WithRetryAfter() throws Exception {
        // Arrange
        publicEndpoint("limited", 2);
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/limited")).build();

        // Act
        HttpResponse<String> first = http.send(request, HttpResponse.BodyHandlers.ofString());
        http.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> third = http.send(request, HttpResponse.BodyHandlers.ofString());

        // Assert
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("RateLimit-Limit")).contains("2");
        assertThat(first.headers().firstValue("RateLimit-Remaining")).contains("1");
        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).isPresent();
        assertThat(third.body()).contains("rate_limited");
    }

    @Test
    void perIpLimitAppliesToPublicEndpoints() {
        // Arrange
        publicEndpoint("per-ip", null);
        policy("off", List.of(), 1);

        // Act + Assert
        call("per-ip").expect(200);
        call("per-ip").expectProblem(429, "rate_limited");
    }

    @Test
    void invalidEntriesAndEmptyAllowListsAreRefused() {
        // Act + Assert
        admin.put(ws("/serving-policy"), Map.of("firewall", Map.of("mode", "deny", "entries", List.of("example.com"))))
                .expectProblem(400, "invalid_ip_range");
        admin.put(ws("/serving-policy"), Map.of("firewall", Map.of("mode", "allow", "entries", List.of())))
                .expectProblem(400, "empty_allow_list");
    }
}
