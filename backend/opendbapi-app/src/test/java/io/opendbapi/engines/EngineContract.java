package io.opendbapi.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.ApiClient;
import io.opendbapi.AppTestSupport;
import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.JdbcConfig;
import io.opendbapi.core.datasource.JdbcDialect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * What every analytics engine must do through OpenDBAPI (spec §4.6): connect, browse, page,
 * preview, stream, and refuse writes when read-only. Each subclass starts one real engine with
 * a table {@code orders(id, customer, total)} holding ids 1 to 5.
 *
 * <p>Tagged {@code engines}: the images are large, so these run in their own CI job
 * ({@code mvn verify -Dtest.excludedGroups=none -Dgroups=engines}).
 */
@Tag("engines")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "ENGINE_TEST_PASSWORD=" + EngineContract.PASSWORD)
abstract class EngineContract extends AppTestSupport {

    static final String PASSWORD = "Engine-test-1";

    @Autowired DataSourceRegistry registry;

    ApiClient admin;
    String ws;
    private final HttpClient http = HttpClient.newHttpClient();

    abstract JdbcDialect dialect();

    abstract String host();

    abstract int port();

    /** The database field, e.g. a Trino {@code catalog.schema}. */
    abstract String database();

    abstract String username();

    /** Null when the engine runs without authentication. */
    abstract String password();

    /** The schema the table browser shows {@code orders} in. */
    abstract String schema();

    /** Inserts one row into {@code orders}. */
    String insertSql() {
        return "insert into orders (id, customer, total) values (#{id}, 'new', 1)";
    }

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "en-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Engine " + ws)).expect(201);
    }

    Map<String, Object> settings(boolean readOnly) {
        Map<String, Object> s = new HashMap<>(Map.of("name", dialect().name().toLowerCase() + (readOnly ? "-ro" : "-rw"),
                "dialect", dialect().name(), "host", host(), "port", port(), "database", database(), "username", username(),
                "properties", Map.of(), "readOnly", readOnly, "pool", Map.of()));
        s.put("password", password());
        return s;
    }

    String datasource(boolean readOnly) {
        return admin.post("/admin/api/ws/" + ws + "/datasources", settings(readOnly)).expect(201).json().path("id").asText();
    }

    Map<String, Object> definition(String dsId, String sql, Map<String, Object> extra) {
        Map<String, Object> def = new HashMap<>(Map.of("params", new ArrayList<>(), "task", Map.of("type", "sql", "datasourceId", dsId,
                "transaction", false, "statements", List.of(Map.of("id", "main", "template", sql)))));
        def.putAll(extra);
        return def;
    }

    String publish(String dsId, String method, String path, Map<String, Object> def) {
        String base = "/admin/api/ws/" + ws + "/endpoints";
        String id = admin.post(base, Map.of("name", path, "method", method, "path", path, "access", "public", "datasourceId", dsId))
                .expect(201).json().path("summary").path("id").asText();
        admin.patch(base + "/" + id, Map.of("name", path, "method", method, "path", path, "access", "public", "definition", def))
                .expect(200);
        admin.post(base + "/" + id + ":publish", Map.of()).expect(200);
        return id;
    }

    HttpResponse<String> call(String method, String pathAndQuery, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/" + pathAndQuery));
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void connectionTestReachesTheEngine() {
        // Act
        JsonNode result = admin.post("/admin/api/ws/" + ws + "/datasources/test", Map.of("settings", settings(true))).expect(200).json();

        // Assert
        assertThat(result.path("ok").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.path("product").asText()).isNotBlank();
    }

    @Test
    void tableBrowserShowsTheSeededTable() {
        // Arrange
        String ds = "/admin/api/ws/" + ws + "/datasources/" + datasource(true);

        // Act
        JsonNode schemas = admin.get(ds + "/schemas").expect(200).json();
        JsonNode tables = admin.get(ds + "/tables?schema=" + schema()).expect(200).json();
        JsonNode columns = admin.get(ds + "/columns?schema=" + schema() + "&table=orders").expect(200).json();

        // Assert
        assertThat(ApiClient.JSON.convertValue(schemas, List.class)).contains(schema());
        assertThat(tables.path("items").findValuesAsText("name")).contains("orders");
        assertThat(columns.findValuesAsText("name")).containsExactly("id", "customer", "total");
    }

    @Test
    void publishedEndpointPagesThroughRows() throws Exception {
        // Arrange
        String dsId = datasource(true);
        publish(dsId, "GET", "orders", definition(dsId, "select id, customer from orders order by id",
                Map.of("pagination", Map.of("enabled", true, "defaultSize", 2, "maxSize", 10, "count", true))));

        // Act
        JsonNode page2 = ApiClient.JSON.readTree(call("GET", "orders?page=2&size=2", null).body());

        // Assert
        assertThat(page2.path("data").findValuesAsText("id")).as(page2.toString()).containsExactly("3", "4");
        assertThat(page2.path("meta").path("total").asInt()).isEqualTo(5);
        assertThat(page2.path("meta").path("hasNext").asBoolean()).isTrue();
    }

    @Test
    void previewRunsTheDraft() {
        // Arrange
        String dsId = datasource(true);
        String id = admin.post("/admin/api/ws/" + ws + "/endpoints", Map.of("name", "p", "method", "GET", "path", "p", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();

        // Act
        JsonNode preview = admin.post("/admin/api/ws/" + ws + "/endpoints/" + id + "/preview", Map.of("method", "GET", "path", "p",
                "definition", definition(dsId, "select id, total from orders where id <= 3 order by id", Map.of()))).expect(200).json();

        // Assert
        assertThat(preview.path("rows")).as(preview.toString()).hasSize(3);
    }

    @Test
    void streamsNdjson() throws Exception {
        // Arrange
        String dsId = datasource(true);
        publish(dsId, "GET", "all", definition(dsId, "select id from orders order by id",
                Map.of("streaming", Map.of("allowed", true))));

        // Act
        HttpResponse<String> streamed = call("GET", "all?format=ndjson", null);

        // Assert
        assertThat(streamed.statusCode()).as(streamed.body()).isEqualTo(200);
        String[] lines = streamed.body().split("\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo("{\"id\":1}");
    }

    @Test
    void readOnlyDatasourceRefusesWritesInTheDriverOrServer() {
        // Arrange: past OpenDBAPI's own SQL checks, straight on a pooled connection
        OffsetDateTime now = OffsetDateTime.now();
        Datasource ds = new Datasource(UUID.randomUUID(), UUID.randomUUID(), "ro", null, "jdbc",
                new JdbcConfig(dialect(), host(), port(), database(), username(), Map.of(), null),
                password() == null ? null : "env:ENGINE_TEST_PASSWORD", true, null, now, now);

        // Act + Assert: autocommit as for a plain endpoint query
        assertThatThrownBy(() -> {
            try (Connection c = registry.connection(ds); Statement s = c.createStatement()) {
                s.executeUpdate("insert into orders (id, customer, total) values (99, 'x', 1)");
            }
        }).satisfies(e -> assertThat(e.getMessage()).containsPattern("(?i)read[- ]?only"));
        registry.evict(ds.id());
    }

    /** Engines whose rollback does not undo writes. */
    abstract static class WithoutTransactions extends EngineContract {

        @Test
        void previewRefusesWritesItCannotRollBack() {
            // Arrange
            String dsId = datasource(false);
            String id = admin.post("/admin/api/ws/" + ws + "/endpoints", Map.of("name", "w", "method", "POST", "path", "w",
                    "access", "public", "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
            Map<String, Object> def = definition(dsId, insertSql(), Map.of());
            def.put("params", List.of(Map.of("name", "id", "in", "body", "type", "bigint", "required", true)));

            // Act + Assert
            admin.post("/admin/api/ws/" + ws + "/endpoints/" + id + "/preview", Map.of("method", "POST", "path", "w",
                    "definition", def, "params", Map.of("id", 50))).expectProblem(400, "preview_cannot_roll_back");
        }

        @Test
        void transactionsAreRefusedAtValidation() {
            // Arrange
            String dsId = datasource(false);
            String base = "/admin/api/ws/" + ws + "/endpoints";
            String id = admin.post(base, Map.of("name", "t", "method", "POST", "path", "t", "access", "public", "datasourceId", dsId))
                    .expect(201).json().path("summary").path("id").asText();
            Map<String, Object> def = definition(dsId, insertSql(), Map.of());
            def.put("params", List.of(Map.of("name", "id", "in", "body", "type", "bigint", "required", true)));
            def.put("task", Map.of("type", "sql", "datasourceId", dsId, "transaction", true,
                    "statements", List.of(Map.of("id", "main", "template", insertSql()))));
            admin.patch(base + "/" + id, Map.of("name", "t", "method", "POST", "path", "t", "access", "public", "definition", def))
                    .expect(200);

            // Act
            JsonNode validation = admin.post(base + "/" + id + ":validate", null).expect(200).json();

            // Assert
            assertThat(validation.path("ok").asBoolean()).isFalse();
            assertThat(validation.path("findings").findValuesAsText("code")).contains("transaction_unsupported");
        }

        @Test
        void publishedWriteEndpointRunsWithoutATransaction() throws Exception {
            // Arrange
            String dsId = datasource(false);
            Map<String, Object> def = definition(dsId, insertSql(), Map.of());
            def.put("params", List.of(Map.of("name", "id", "in", "body", "type", "bigint", "required", true)));
            publish(dsId, "POST", "add", def);

            // Act
            HttpResponse<String> written = call("POST", "add", "{\"id\": 100}");

            // Assert
            assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        }
    }
}
