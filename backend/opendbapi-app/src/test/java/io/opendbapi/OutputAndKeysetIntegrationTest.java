package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Result mapping (spec §9.2) and keyset pagination (spec §9.3) on the serving path. */
class OutputAndKeysetIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;
    private String table;

    @BeforeEach
    void arrange() throws Exception {
        admin = signedInAdmin();
        ws = "ok-" + UUID.randomUUID().toString().substring(0, 8);
        table = "events_" + ws.substring(3);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Output " + ws)).expect(201);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create table " + table + " (id int primary key, created_at timestamp not null, amount numeric(12,2), kind text)");
            // 25 events, several sharing a timestamp so the id tie-breaker matters
            s.execute("insert into " + table + " select g, timestamp '2026-01-01' + ((g / 3) || ' hours')::interval, g * 1.25, "
                    + "case when g % 2 = 0 then 'even' else 'odd' end from generate_series(1, 25) g");
        }
        Map<String, Object> ds = new HashMap<>(Map.of("name", "events", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private String endpoint(String path, String sql, Map<String, Object> extra) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> definition = new HashMap<>(Map.of("params", List.of(),
                "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false, "statements", List.of(Map.of("id", "main", "template", sql)))));
        definition.putAll(extra);
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "public",
                "definition", definition)).expect(200);
        return id;
    }

    private ApiClient.Response call(String path) {
        return client().get("/api/" + ws + "/" + path);
    }

    @Test
    void keysetPagesWalkEveryRowOnceInKeyOrder() {
        // Arrange
        String id = endpoint("events", "select id, created_at, kind from " + table + " order by id", Map.of(
                "pagination", Map.of("enabled", true, "defaultSize", 7, "maxSize", 50, "count", false, "mode", "keyset",
                        "keys", List.of(Map.of("column", "created_at", "descending", true), Map.of("column", "id", "descending", false)))));
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        List<Integer> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode body = call("events" + (cursor == null ? "" : "?after=" + cursor)).expect(200).json();
            body.path("data").forEach(r -> seen.add(r.path("id").asInt()));
            cursor = body.path("meta").path("nextCursor").isNull() ? null : body.path("meta").path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        // Assert: newest hour first, ids ascending within an hour, nothing repeated or lost
        assertThat(pages).isEqualTo(4);
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
        assertThat(seen.subList(0, 4)).containsExactly(24, 25, 21, 22);
        call("events?after=garbage").expectProblem(400, "invalid_cursor");
        String docs = client().get("/api/" + ws + "/openapi.json").expect(200).body();
        assertThat(docs).contains("\"after\"").contains("nextCursor");
    }

    @Test
    void outputIsTypedNestedAndCamelCased() {
        // Arrange
        String id = endpoint("kinds", "select kind as kind_name, count(*) as event_count, e.id as ev_id, e.amount as ev_amount "
                        + "from " + table + " e where e.id <= 4 group by kind, e.id, e.amount order by kind, e.id",
                Map.of("output", Map.of("naming", "camelCase", "shape", "list", "types", Map.of("ev_amount", "decimal_string"),
                        "nest", List.of(Map.of("key", "kind_name", "collections", List.of(Map.of("as", "events", "prefix", "ev_", "key", "ev_id")))))));
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        JsonNode data = call("kinds").expect(200).json().path("data");

        // Assert
        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("kindName").asText()).isEqualTo("even");
        assertThat(data.get(0).path("events")).hasSize(2);
        assertThat(data.get(0).path("events").get(0).path("amount").asText()).isEqualTo("2.50");
        assertThat(data.get(0).path("events").get(0).path("amount").isTextual()).isTrue();
        JsonNode docs = client().get("/api/" + ws + "/openapi.json").expect(200).json();
        JsonNode item = docs.findPath("data").path("items").path("properties");
        assertThat(item.has("kindName")).isTrue();
        assertThat(item.path("events").path("items").path("properties").path("amount").path("type").get(0).asText()).isEqualTo("string");
    }

    @Test
    void singleShapeAnswers404WhenNothingMatches() {
        // Arrange
        String id = endpoint("one", "select id, kind from " + table + " where id = #{id}", Map.of(
                "params", List.of(Map.of("name", "id", "in", "query", "type", "bigint", "required", true)),
                "output", Map.of("shape", "single")));
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act + Assert
        assertThat(call("one?id=3").expect(200).json().path("data").path("kind").asText()).isEqualTo("odd");
        call("one?id=999").expectProblem(404, "not_found");
    }

    @Test
    void validationRefusesOutputThatCannotWork() {
        // Arrange
        String id = endpoint("bad", "select id, kind from " + table, Map.of(
                "pagination", Map.of("enabled", true, "defaultSize", 5, "maxSize", 10, "count", true, "mode", "keyset",
                        "keys", List.of(Map.of("column", "missing", "descending", false))),
                "output", Map.of("shape", "single", "types", Map.of("kind", "rot13"))));

        // Act
        JsonNode findings = admin.post(ws("/endpoints/" + id + ":validate"), Map.of()).expect(200).json();

        // Assert
        String messages = findings.findValuesAsText("message").toString();
        assertThat(messages).contains("cannot be paginated").contains("'missing' is not a column").contains("rot13");
    }
}
