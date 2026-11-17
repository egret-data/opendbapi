package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Parameter rules (spec §13.1) and alert webhooks (spec §13.2). */
class RulesAndAlertsIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;
    private HttpServer hook;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void arrange() throws Exception {
        admin = signedInAdmin();
        ws = "ra-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Rules " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hook.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        hook.start();
    }

    @AfterEach
    void stop() {
        hook.stop(0);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private String hookUrl(String path) {
        return "http://127.0.0.1:" + hook.getAddress().getPort() + path;
    }

    private String endpoint(String path, String sql, List<Map<String, Object>> params, List<Map<String, Object>> rules, boolean alerts) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", Map.of("params", params, "rules", rules, "alerts", Map.of("enabled", alerts),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", sql)))))).expect(200);
        return id;
    }

    private ApiClient.Response call(String path) {
        return client().get("/api/" + ws + "/" + path);
    }

    private static final List<Map<String, Object>> RANGE = List.of(
            Map.of("name", "from", "in", "query", "type", "bigint", "required", false),
            Map.of("name", "to", "in", "query", "type", "bigint", "required", false));

    @Test
    void rulesRefuseBadParameterCombinationsBeforeTheQuery() {
        // Arrange
        String id = endpoint("range", "select #{from}::int8 as a, #{to}::int8 as b", RANGE,
                List.of(Map.of("test", "from == null or to == null or to >= from", "message", "to must not be before from.")), false);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        ApiClient.Response bad = call("range?from=5&to=1");
        ApiClient.Response good = call("range?from=1&to=5");

        // Assert
        bad.expectProblem(400, "rule_failed");
        assertThat(bad.json().path("detail").asText()).isEqualTo("to must not be before from.");
        good.expect(200);
    }

    @Test
    void validationRejectsRulesThatCannotWork() {
        // Arrange
        String id = endpoint("checked", "select #{from}::int8 as a, #{to}::int8 as b", RANGE,
                List.of(Map.of("test", "missing > 1", "message", "x"), Map.of("test", "from >", "message", "y"),
                        Map.of("test", "from > 0", "message", "")), false);

        // Act
        JsonNode findings = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json();

        // Assert
        assertThat(findings.findValuesAsText("message").toString())
                .contains("Rule 1 reads 'missing'").contains("Rule 2").contains("Rule 3 needs a message");
    }

    @Test
    void failingEndpointAlertsOnceWithinTheWindowAndWithoutParameterValues() throws Exception {
        // Arrange
        admin.post(ws("/alert-channels"), Map.of("name", "ops", "kind", "webhook", "url", hookUrl("/hook"), "enabled", true))
                .expect(201);
        String id = endpoint("broken", "select 1 / (#{n}::int - #{n}::int) as boom",
                List.of(Map.of("name", "n", "in", "query", "type", "bigint", "required", true)), List.of(), true);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        call("broken?n=424242").expectProblem(500, "query_failed");
        call("broken?n=424242").expectProblem(500, "query_failed");

        // Assert
        long deadline = System.currentTimeMillis() + 5_000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertThat(received).hasSize(1);
        JsonNode event = ApiClient.JSON.readTree(received.get(0));
        assertThat(event.path("event").asText()).isEqualTo("endpoint_failed");
        assertThat(event.path("code").asText()).isEqualTo("query_failed");
        assertThat(event.path("endpoint").path("path").asText()).isEqualTo("/broken");
        assertThat(received.get(0)).doesNotContain("424242");
    }

    @Test
    void channelsHideTheirUrlAndReportDeliveryProblems() {
        // Arrange
        String ok = admin.post(ws("/alert-channels"), Map.of("name", "ok", "kind", "slack", "url", hookUrl("/hook"), "enabled", true))
                .expect(201).json().path("id").asText();
        String broken = admin.post(ws("/alert-channels"), Map.of("name", "broken", "kind", "teams", "url", hookUrl("/broken"),
                "enabled", true)).expect(201).json().path("id").asText();

        // Act
        JsonNode okTest = admin.post(ws("/alert-channels/" + ok + ":test"), Map.of()).expect(200).json();
        JsonNode brokenTest = admin.post(ws("/alert-channels/" + broken + ":test"), Map.of()).expect(200).json();
        String listed = admin.get(ws("/alert-channels")).expect(200).body();
        admin.post(ws("/alert-channels"), Map.of("name", "ftp", "kind", "slack", "url", "ftp://example.com/x", "enabled", true))
                .expectProblem(400, "invalid_url");

        // Assert
        assertThat(okTest.path("ok").asBoolean()).isTrue();
        assertThat(brokenTest.path("ok").asBoolean()).isFalse();
        assertThat(brokenTest.path("status").asInt()).isEqualTo(500);
        assertThat(received.get(0)).contains("\"text\"").contains(ws);
        assertThat(listed).contains("127.0.0.1").doesNotContain("/hook").contains("The webhook answered HTTP 500.");
    }
}
