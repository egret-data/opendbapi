package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Groovy fragment scripts as endpoint tasks (spec §7.4). */
class ScriptTaskIntegrationTest extends AppTestSupport {

    private static final String SCRIPT = """
            def q = sql"select g as n from generate_series(1, 20) g where 1 = 1"
            if (params.min) q += sql" and g >= ${params.min}"
            if (params.only) q += sql" and g in (${params.only})"
            q += orderBy([up: 'g asc', down: 'g desc'], params.sort, 'g asc')
            return q
            """;

    private ApiClient admin;
    private String ws;
    private String dsId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "sc-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Scripts " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private Map<String, Object> update(String source) {
        return Map.of("name", "numbers", "method", "GET", "path", "numbers", "access", "public",
                "definition", Map.of("params", List.of(
                                Map.of("name", "min", "in", "query", "type", "bigint", "required", false),
                                Map.of("name", "only", "in", "query", "type", "array<bigint>", "required", false),
                                Map.of("name", "sort", "in", "query", "type", "string", "required", false)),
                        "pagination", Map.of("enabled", true, "defaultSize", 5, "maxSize", 50, "count", true),
                        "task", Map.of("type", "script", "datasourceId", dsId, "transaction", false, "language", "groovy",
                                "mode", "fragment", "source", source)));
    }

    private String endpoint(ApiClient as, String source) {
        String id = admin.post(ws("/endpoints"), Map.of("name", "numbers", "method", "GET", "path", "numbers", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        as.patch(ws("/endpoints/" + id), update(source)).expect(200);
        return id;
    }

    private ApiClient member(String role) {
        String name = role.replace("_", "") + "-" + UUID.randomUUID().toString().substring(0, 8);
        String id = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!", "systemAdmin", false))
                .expect(201).json().path("id").asText();
        admin.post(ws("/members"), Map.of("userId", id, "role", role)).expect(201);
        ApiClient c = client();
        c.login(name, name + "-password!").expect(204);
        return c;
    }

    @Test
    void scriptQueriesAreServedWithBoundValuesAndPagination() {
        // Arrange
        String id = endpoint(admin, SCRIPT);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);

        // Act
        JsonNode page = client().get("/api/" + ws + "/numbers?min=15&sort=down&size=3").expect(200).json();
        JsonNode only = client().get("/api/" + ws + "/numbers?only=2&only=4").expect(200).json();
        JsonNode injected = client().get("/api/" + ws + "/numbers?sort=g%3B%20drop%20table%20x").expect(200).json();

        // Assert
        assertThat(page.path("data").findValuesAsText("n")).containsExactly("20", "19", "18");
        assertThat(page.path("meta").path("total").asInt()).isEqualTo(6);
        assertThat(only.path("data").findValuesAsText("n")).containsExactly("2", "4");
        assertThat(injected.path("data").get(0).path("n").asInt()).isEqualTo(1);
    }

    @Test
    void validationReportsSandboxAndScriptErrors() {
        // Arrange
        String escape = endpoint(admin, "Runtime.getRuntime().exec('id')\nreturn sql\"select 1\"");
        String plain = endpoint2("return 'select 1'");

        // Act
        JsonNode escapeFindings = admin.post(ws("/endpoints/" + escape + ":validate"), Map.of()).expect(200).json();
        JsonNode plainFindings = admin.post(ws("/endpoints/" + plain + ":validate"), Map.of()).expect(200).json();

        // Assert
        assertThat(escapeFindings.findValuesAsText("message").toString()).contains("not available in scripts");
        assertThat(plainFindings.findValuesAsText("message").toString()).contains("must return a fragment");
        admin.post(ws("/endpoints/" + escape + ":publish"), Map.of()).expectProblem(409, "validation_failed");
    }

    private String endpoint2(String source) {
        String id = admin.post(ws("/endpoints"), Map.of("name", "other", "method", "GET", "path", "other", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> body = new HashMap<>(update(source));
        body.put("name", "other");
        body.put("path", "other");
        admin.patch(ws("/endpoints/" + id), body).expect(200);
        return id;
    }

    @Test
    void onlyScriptAuthorsWriteOrPreviewScripts() {
        // Arrange
        String id = endpoint(admin, SCRIPT);
        ApiClient author = member("author");
        ApiClient scripter = member("script_author");
        Map<String, Object> changed = update(SCRIPT.replace("g asc'", "g asc nulls last'"));
        Map<String, Object> renamed = new HashMap<>(update(SCRIPT));
        renamed.put("name", "renamed");

        // Act + Assert
        author.patch(ws("/endpoints/" + id), changed).expectProblem(403, "forbidden");
        author.post(ws("/endpoints/" + id + "/preview"), Map.of("method", "GET", "path", "numbers",
                "definition", update(SCRIPT).get("definition"), "params", Map.of())).expectProblem(403, "forbidden");
        author.patch(ws("/endpoints/" + id), renamed).expect(200);
        scripter.patch(ws("/endpoints/" + id), changed).expect(200);
        JsonNode preview = scripter.post(ws("/endpoints/" + id + "/preview"), Map.of("method", "GET", "path", "numbers",
                "definition", changed.get("definition"), "params", Map.of("min", "19"))).expect(200).json();
        assertThat(preview.path("rows").findValuesAsText("n")).containsExactly("19", "20");
    }
}
