package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Secrets managed by OpenDBAPI and referred to as secret:NAME (spec §4.5). */
class SecretsIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "se-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Secrets " + ws)).expect(201);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private Map<String, Object> datasource(String name, String password) {
        return new HashMap<>(Map.of("name", name, "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", password, "properties", Map.of(), "readOnly", true, "pool", Map.of()));
    }

    @Test
    void aDatasourcePasswordCanLiveInAManagedSecretAndFollowsItsChanges() {
        // Arrange
        admin.post(ws("/secrets"), Map.of("name", "pg-password", "description", "Test database", "value", POSTGRES.getPassword()))
                .expect(201);
        String dsId = admin.post(ws("/datasources"), datasource("main", "secret:pg-password")).expect(201).json().path("id").asText();
        String test = ws("/datasources/" + dsId + ":test");

        // Act
        boolean before = admin.post(test, Map.of()).expect(200).json().path("ok").asBoolean();
        admin.patch(ws("/secrets/pg-password"), Map.of("value", "wrong-password")).expect(200);
        JsonNode wrong = admin.post(test, Map.of()).expect(200).json();
        admin.patch(ws("/secrets/pg-password"), Map.of("value", POSTGRES.getPassword())).expect(200);
        boolean after = admin.post(test, Map.of()).expect(200).json().path("ok").asBoolean();
        JsonNode view = admin.get(ws("/datasources/" + dsId)).expect(200).json();
        String listed = admin.get(ws("/secrets")).expect(200).body();

        // Assert
        assertThat(before).isTrue();
        assertThat(wrong.path("ok").asBoolean()).isFalse();
        assertThat(after).isTrue();
        assertThat(view.path("passwordSecret").asText()).isEqualTo("pg-password");
        assertThat(listed).contains("pg-password").contains("\"kind\":\"datasource\"").doesNotContain(POSTGRES.getPassword())
                .doesNotContain("enc:v1:");
    }

    @Test
    void secretsInUseCannotBeDeletedAndUnknownOnesAreRefused() {
        // Arrange
        admin.post(ws("/secrets"), Map.of("name", "db", "value", POSTGRES.getPassword())).expect(201);
        String dsId = admin.post(ws("/datasources"), datasource("main", "secret:db")).expect(201).json().path("id").asText();

        // Act + Assert
        admin.post(ws("/datasources"), datasource("other", "secret:missing")).expectProblem(400, "secret_unavailable");
        admin.delete(ws("/secrets/db")).expectProblem(409, "secret_in_use");
        admin.patch(ws("/datasources/" + dsId), datasource("main", POSTGRES.getPassword())).expect(200);
        admin.delete(ws("/secrets/db")).expect(204);
        admin.post(ws("/secrets"), Map.of("name", "Bad Name", "value", "x")).expectProblem(400, "invalid_secret");
        admin.post(ws("/secrets"), Map.of("name", "dup", "value", "x")).expect(201);
        admin.post(ws("/secrets"), Map.of("name", "dup", "value", "y")).expectProblem(409, "name_taken");
    }

    @Test
    void anAlertChannelCanReadItsUrlFromASecret() throws Exception {
        // Arrange
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hook.start();
        try {
            admin.post(ws("/secrets"), Map.of("name", "ops-hook", "value", "http://127.0.0.1:" + hook.getAddress().getPort() + "/hook"))
                    .expect(201);

            // Act
            JsonNode channel = admin.post(ws("/alert-channels"), Map.of("name", "ops", "kind", "slack", "url", "secret:ops-hook",
                    "enabled", true)).expect(201).json();
            JsonNode tested = admin.post(ws("/alert-channels/" + channel.path("id").asText() + ":test"), Map.of()).expect(200).json();

            // Assert
            assertThat(channel.path("urlHost").asText()).isEqualTo("127.0.0.1");
            assertThat(tested.path("ok").asBoolean()).isTrue();
            assertThat(received).hasSize(1);
            assertThat(admin.get(ws("/secrets")).body()).contains("alert_channel");
        } finally {
            hook.stop(0);
        }
    }
}
