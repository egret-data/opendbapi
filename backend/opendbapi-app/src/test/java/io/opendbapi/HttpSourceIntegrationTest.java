package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP datasources and HTTP request tasks (spec §4.6, §6), against a local upstream API. */
class HttpSourceIntegrationTest extends AppTestSupport {

    /** What the upstream received. */
    record Received(String method, String uri, String authorization, String tenant, String body) {
    }

    private HttpServer upstream;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private ApiClient admin;
    private String ws;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void arrange() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", this::answer);
        upstream.start();
        admin = signedInAdmin();
        ws = "http-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "HTTP " + ws)).expect(201);
    }

    @AfterEach
    void stop() {
        upstream.stop(0);
    }

    private void answer(HttpExchange ex) throws java.io.IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
                ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("X-Tenant"), body));
        String path = ex.getRequestURI().getPath();
        int status = 200;
        String json;
        if (path.startsWith("/v1/orders/")) {
            json = "{\"data\": {\"items\": [{\"id\": 1, \"customer\": \"ana\", \"email\": \"ana@example.com\"},"
                    + " {\"id\": 2, \"customer\": \"bo\", \"total\": 20.5}]}}";
        } else if (path.equals("/v1/missing")) {
            status = 404;
            json = "{}";
        } else if (path.equals("/v1/broken")) {
            status = 500;
            json = "{}";
        } else if (path.equals("/v1/text")) {
            json = "not json";
        } else if (path.equals("/v1/moved")) {
            ex.getResponseHeaders().add("Location", "/v1/secret");
            status = 302;
            json = "";
        } else if (path.equals("/v1/notes")) {
            status = 201;
            json = "{\"id\": 99}";
        } else {
            json = "{\"ok\": true}";
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private Map<String, Object> settings(boolean readOnly) {
        Map<String, Object> s = new HashMap<>();
        s.put("name", "orders-api" + (readOnly ? "" : "-rw"));
        s.put("type", "http");
        s.put("http", Map.of("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "auth", "bearer",
                "headers", Map.of("X-Tenant", "acme")));
        s.put("password", "upstream-token");
        s.put("readOnly", readOnly);
        return s;
    }

    private String datasource(boolean readOnly) {
        return admin.post("/admin/api/ws/" + ws + "/datasources", settings(readOnly)).expect(201).json().path("id").asText();
    }

    private static Map<String, Object> field(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    private static Map<String, Object> param(String name, String in, String type) {
        return Map.of("name", name, "in", in, "type", type, "required", false);
    }

    private Map<String, Object> definition(String dsId, String method, String path, List<Map<String, Object>> query,
                                           List<Map<String, Object>> body, String resultPath, List<Map<String, Object>> params) {
        Map<String, Object> request = new HashMap<>(Map.of("method", method, "path", path, "query", query, "body", body));
        request.put("resultPath", resultPath);
        return new HashMap<>(Map.of("params", params, "task", Map.of("type", "http", "datasourceId", dsId, "transaction", false,
                "request", request)));
    }

    private String endpoint(String dsId, String method, String path, Map<String, Object> def) {
        String base = "/admin/api/ws/" + ws + "/endpoints";
        String id = admin.post(base, Map.of("name", path, "method", method, "path", path, "access", "public", "datasourceId", dsId))
                .expect(201).json().path("summary").path("id").asText();
        admin.patch(base + "/" + id, Map.of("name", path, "method", method, "path", path, "access", "public", "definition", def))
                .expect(200);
        return id;
    }

    private void publish(String id) {
        admin.post("/admin/api/ws/" + ws + "/endpoints/" + id + ":publish", Map.of()).expect(200);
    }

    private HttpResponse<String> call(String method, String pathAndQuery, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/" + pathAndQuery));
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getEndpointCallsTheApiWithItsCredentialsAndAnswersItsRows() throws Exception {
        // Arrange
        String dsId = datasource(true);
        JsonNode tested = admin.post("/admin/api/ws/" + ws + "/datasources/" + dsId + ":test", null).expect(200).json();
        String id = endpoint(dsId, "GET", "customers/{id}/orders", definition(dsId, "GET", "/orders/{id}",
                List.of(field("status", "status"), field("tag", "tags")), List.of(), "/data/items",
                List.of(param("id", "path", "bigint"), param("status", "query", "string"), param("tags", "query", "array<string>"))));
        publish(id);

        // Act
        HttpResponse<String> answer = call("GET", "customers/7/orders?status=open%20now&tags=a&tags=b", null);

        // Assert
        assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode data = ApiClient.JSON.readTree(answer.body()).path("data");
        assertThat(data.findValuesAsText("customer")).containsExactly("ana", "bo");
        assertThat(data.get(1).has("email")).isTrue();
        Received sent = received.get(received.size() - 1);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri()).isEqualTo("/v1/orders/7?status=open%20now&tag=a&tag=b");
        assertThat(sent.authorization()).isEqualTo("Bearer upstream-token");
        assertThat(sent.tenant()).isEqualTo("acme");
    }

    @Test
    void pathValuesCannotLeaveTheirSegment() throws Exception {
        // Arrange
        String dsId = datasource(true);
        publish(endpoint(dsId, "GET", "o/{key}", definition(dsId, "GET", "/orders/{key}", List.of(), List.of(), "",
                List.of(param("key", "path", "string")))));

        // Act
        HttpResponse<String> answer = call("GET", "o/a%20b%3Fx%3D1%26y", null);

        // Assert: the value stays one path segment; ? = & do not start a query
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        assertThat(received.get(received.size() - 1).uri()).isEqualTo("/v1/orders/a%20b%3Fx%3D1%26y");
    }

    @Test
    void upstreamErrorsBecomeProblems() throws Exception {
        // Arrange
        String dsId = datasource(true);
        for (String p : List.of("missing", "broken", "text", "moved")) {
            publish(endpoint(dsId, "GET", p, definition(dsId, "GET", "/" + p, List.of(), List.of(), "", List.of())));
        }

        // Act + Assert
        assertThat(call("GET", "missing", null).statusCode()).isEqualTo(404);
        HttpResponse<String> broken = call("GET", "broken", null);
        assertThat(broken.statusCode()).isEqualTo(502);
        assertThat(broken.body()).contains("upstream_failed").contains("HTTP 500");
        assertThat(call("GET", "text", null).body()).contains("not JSON");
        HttpResponse<String> moved = call("GET", "moved", null);
        assertThat(moved.body()).contains("HTTP 302");
        assertThat(received).noneMatch(r -> r.uri().contains("secret"));
    }

    @Test
    void postSendsAJsonBodyButPreviewDoesNot() throws Exception {
        // Arrange
        String dsId = datasource(false);
        Map<String, Object> def = definition(dsId, "POST", "/notes", List.of(),
                List.of(field("customerId", "customerId"), field("text", "note")), "",
                List.of(param("customerId", "body", "bigint"), param("note", "body", "string")));
        String id = endpoint(dsId, "POST", "notes", def);

        // Act
        admin.post("/admin/api/ws/" + ws + "/endpoints/" + id + "/preview", Map.of("method", "POST", "path", "notes",
                "definition", def, "params", Map.of("customerId", 1, "note", "hi"))).expectProblem(400, "preview_cannot_roll_back");
        int before = received.size();
        publish(id);
        HttpResponse<String> written = call("POST", "notes", "{\"customerId\": 5, \"note\": \"called back\"}");

        // Assert
        assertThat(before).isZero();
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        assertThat(ApiClient.JSON.readTree(written.body()).path("data").get(0).path("id").asInt()).isEqualTo(99);
        Received sent = received.get(received.size() - 1);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(ApiClient.JSON.readTree(sent.body())).isEqualTo(ApiClient.JSON.readTree("{\"customerId\": 5, \"text\": \"called back\"}"));
    }

    @Test
    void validationKeepsHttpTasksSafeAndConsistent() {
        // Arrange
        String readOnly = datasource(true);
        String base = "/admin/api/ws/" + ws + "/endpoints";
        String write = endpoint(readOnly, "POST", "w", definition(readOnly, "POST", "/notes", List.of(), List.of(), "", List.of()));
        String getWrites = endpoint(readOnly, "GET", "g", definition(readOnly, "DELETE", "/notes", List.of(), List.of(), "", List.of()));
        Map<String, Object> paged = definition(readOnly, "GET", "/orders/1", List.of(), List.of(), "", List.of());
        paged.put("pagination", Map.of("enabled", true, "defaultSize", 10, "maxSize", 20));
        String pagedId = endpoint(readOnly, "GET", "p", paged);
        String sql = endpoint(readOnly, "GET", "s", new HashMap<>(Map.of("params", List.of(), "task", Map.of("type", "sql",
                "datasourceId", readOnly, "transaction", false, "statements", List.of(Map.of("id", "main", "template", "select 1"))))));

        // Act
        java.util.function.Function<String, List<String>> codes = id -> {
            List<String> out = new ArrayList<>();
            admin.post(base + "/" + id + ":validate", null).expect(200).json().path("findings")
                    .forEach(f -> out.add(f.path("code").asText()));
            return out;
        };

        // Assert
        assertThat(codes.apply(write)).contains("read_only_datasource");
        assertThat(codes.apply(getWrites)).contains("write_in_get");
        assertThat(codes.apply(pagedId)).contains("pagination");
        assertThat(codes.apply(sql)).contains("datasource_mismatch");
        admin.get("/admin/api/ws/" + ws + "/datasources/" + readOnly + "/schemas").expectProblem(400, "not_a_database");
    }

    @Test
    void newEndpointsOnAnHttpApiStartAsAnHttpRequest() {
        // Arrange
        String dsId = datasource(true);

        // Act
        JsonNode created = admin.post("/admin/api/ws/" + ws + "/endpoints", Map.of("name", "n", "method", "GET", "path", "n",
                "access", "public", "datasourceId", dsId)).expect(201).json();

        // Assert
        JsonNode task = created.path("definition").path("task");
        assertThat(task.path("type").asText()).isEqualTo("http");
        assertThat(task.path("request").path("method").asText()).isEqualTo("GET");
    }

    @Test
    void settingsAreValidatedAndExportedWithoutTheSecret() {
        // Arrange
        Map<String, Object> bad = settings(true);
        bad.put("http", Map.of("baseUrl", "http://user:pw@example.com", "auth", "none"));
        Map<String, Object> smuggled = settings(true);
        smuggled.put("http", Map.of("baseUrl", "https://api.example.com", "auth", "none", "headers", Map.of("Authorization", "x")));
        String dsId = datasource(true);

        // Act
        admin.post("/admin/api/ws/" + ws + "/datasources", bad).expectProblem(400, "invalid_datasource");
        admin.post("/admin/api/ws/" + ws + "/datasources", smuggled).expectProblem(400, "invalid_datasource");
        JsonNode view = admin.get("/admin/api/ws/" + ws + "/datasources/" + dsId).expect(200).json();
        String exported = admin.get("/admin/api/ws/" + ws + "/export").expect(200).body();

        // Assert
        assertThat(view.path("type").asText()).isEqualTo("http");
        assertThat(view.path("http").path("auth").asText()).isEqualTo("bearer");
        assertThat(view.path("hasPassword").asBoolean()).isTrue();
        assertThat(exported).contains("type: http").contains("baseUrl:").doesNotContain("upstream-token");
    }
}
