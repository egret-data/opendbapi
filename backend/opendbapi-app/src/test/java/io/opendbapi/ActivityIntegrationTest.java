package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.activity.AccessLogWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Access log table, Activity API and metrics (spec §18, §19). */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class ActivityIntegrationTest extends AppTestSupport {

    @Autowired
    private AccessLogWriter accessLog;

    private ApiClient admin;
    private String ws;
    private String endpointId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "act-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Activity " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        endpointId = admin.post(ws("/endpoints"), Map.of("name", "Divide", "method", "GET", "path", "divide/{n}", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + endpointId), Map.of("name", "Divide", "method", "GET", "path", "divide/{n}", "access", "public",
                "definition", Map.of("params", List.of(Map.of("name", "n", "in", "path", "type", "bigint", "required", true)),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", "select 10 / #{n}::int as q")))))).expect(200);
        admin.post(ws("/endpoints/" + endpointId + ":publish"), Map.of()).expect(200);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    @Test
    void callsAreLoggedSummarisedAndFoundByRequestId() {
        // Arrange
        ApiClient caller = client();
        caller.get("/api/" + ws + "/divide/2").expect(200);
        caller.get("/api/" + ws + "/divide/5").expect(200);
        String failedRequest = caller.get("/api/" + ws + "/divide/0").expect(500).json().path("requestId").asText();
        caller.get("/api/" + ws + "/divide/x").expect(400);
        admin.post(ws("/endpoints/" + endpointId + "/try"), Map.of("params", Map.of("n", 1))).expect(200);
        accessLog.flush();

        // Act
        JsonNode summary = admin.get(ws("/activity/summary")).expect(200).json();
        JsonNode withTry = admin.get(ws("/activity/summary?includeTryIt=true")).expect(200).json();
        JsonNode errors = admin.get(ws("/activity/log?status=5xx")).expect(200).json();
        JsonNode byRequest = admin.get(ws("/activity/log?requestId=" + failedRequest)).expect(200).json();

        // Assert
        assertThat(summary.path("requests").asLong()).isEqualTo(4);
        assertThat(summary.path("errors").asLong()).isEqualTo(2);
        assertThat(withTry.path("requests").asLong()).isEqualTo(5);
        assertThat(summary.path("topEndpoints").get(0).path("name").asText()).isEqualTo("Divide");
        assertThat(summary.path("topErrors").findValuesAsText("code")).contains("query_failed", "invalid_param");
        assertThat(summary.path("byDay")).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("error").asText()).contains("division by zero"); // authors see the DB error
        assertThat(byRequest.get(0).path("status").asInt()).isEqualTo(500);
    }

    @Test
    void prometheusExposesRequestAndDatabaseMetrics() throws Exception {
        // Arrange
        client().get("/api/" + ws + "/divide/2").expect(200);

        // Act
        String metrics = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();

        // Assert
        assertThat(metrics).contains("opendbapi_requests_total{").contains("endpoint=\"" + endpointId + "\"")
                .contains("opendbapi_request_duration_seconds_bucket").contains("opendbapi_db_duration_seconds")
                .contains("opendbapi_pool_active{").contains("opendbapi_access_log_dropped_total");
    }

    @Test
    void rangesAreValidated() {
        // Act + Assert
        admin.get(ws("/activity/summary?from=2026-01-01T00:00:00Z&to=2025-01-01T00:00:00Z")).expectProblem(400, "invalid_range");
        admin.get(ws("/activity/log?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z")).expectProblem(400, "invalid_range");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void slowQueriesAreLoggedWithoutParameterValues(org.springframework.boot.test.system.CapturedOutput output) {
        // Arrange
        String dsId = admin.get(ws("/datasources")).json().get(0).path("id").asText();
        String id = admin.post(ws("/endpoints"), Map.of("name", "Slow", "method", "GET", "path", "slow", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", "Slow", "method", "GET", "path", "slow", "access", "public",
                "definition", Map.of("params", List.of(Map.of("name", "secret", "in", "query", "type", "string", "required", true)),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false, "statements", List.of(Map.of(
                                "id", "main", "template", "select pg_sleep(1.05)::text as s, #{secret}::text as v")))))).expect(200);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        client().get("/api/" + ws + "/slow?secret=hunter2").expect(200);

        // Assert
        assertThat(output.getOut()).contains("Slow query").contains("parameters [secret]").contains("pg_sleep(1.05)")
                .doesNotContain("hunter2");
    }
}
