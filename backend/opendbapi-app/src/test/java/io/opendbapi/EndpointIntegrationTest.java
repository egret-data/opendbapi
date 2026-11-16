package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Author an endpoint, publish it, call it (spec §5, §9). */
class EndpointIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private ApiClient viewer;
    private ApiClient caller;
    private String ws;
    private String datasourceId;

    @BeforeAll
    static void customerData() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create schema if not exists crm");
            s.execute("create table if not exists crm.customers (id int primary key, name text not null, tier text, "
                    + "joined date, balance numeric(12,2), active boolean)");
            s.execute("truncate crm.customers");
            StringBuilder insert = new StringBuilder("insert into crm.customers values ");
            for (int i = 1; i <= 30; i++) {
                insert.append(i > 1 ? "," : "").append("(%d, 'customer %02d', '%s', date '2026-01-01' + %d, %d.25, %s)"
                        .formatted(i, i, i % 3 == 0 ? "gold" : "silver", i, i * 100, i % 2 == 0));
            }
            s.execute(insert.toString());
            s.execute("create table if not exists crm.notes (id serial primary key, customer_id int, body text)");
            s.execute("do $$ begin if not exists (select from pg_roles where rolname = 'crm_app') then "
                    + "create role crm_app login password 'crm-app-pass'; end if; end $$");
            s.execute("grant usage on schema crm to crm_app");
            s.execute("grant select, insert on all tables in schema crm to crm_app");
            s.execute("grant usage on all sequences in schema crm to crm_app");
        }
    }

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "ep-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Endpoints " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "crm", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", "crm_app",
                "password", "crm-app-pass", "properties", Map.of("currentSchema", "crm"), "readOnly", false, "pool", Map.of()));
        datasourceId = admin.post("/admin/api/ws/" + ws + "/datasources", ds).expect(201).json().path("id").asText();
        String name = "viewer-" + UUID.randomUUID().toString().substring(0, 8);
        String id = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!", "systemAdmin", false))
                .expect(201).json().path("id").asText();
        admin.post("/admin/api/ws/" + ws + "/members", Map.of("userId", id, "role", "viewer")).expect(201);
        viewer = client();
        viewer.login(name, name + "-password!").expect(204);
        caller = client();
    }

    private String base() {
        return "/admin/api/ws/" + ws + "/endpoints";
    }

    private String create(String method, String path, String access) {
        return admin.post(base(), Map.of("name", path, "method", method, "path", path, "access", access, "datasourceId", datasourceId))
                .expect(201).json().path("summary").path("id").asText();
    }

    private static Map<String, Object> param(String name, String in, String type, boolean required) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("in", in);
        p.put("type", type);
        p.put("required", required);
        return p;
    }

    private void save(String id, String method, String path, String access, List<Map<String, Object>> params, String sql,
                      Map<String, Object> pagination) {
        Map<String, Object> definition = new HashMap<>();
        definition.put("params", params);
        definition.put("task", Map.of("type", "sql", "datasourceId", datasourceId, "transaction", false,
                "statements", List.of(Map.of("id", "main", "template", sql))));
        if (pagination != null) {
            definition.put("pagination", pagination);
        }
        admin.patch(base() + "/" + id, Map.of("name", path, "method", method, "path", path, "access", access, "definition", definition))
                .expect(200);
    }

    private ApiClient.Response call(String method, String pathAndQuery, Object body) {
        String url = "/api/" + ws + "/" + pathAndQuery;
        return switch (method) {
            case "POST" -> caller.withoutCsrf().post(url, body);
            default -> caller.get(url);
        };
    }

    @Test
    void publishServesTheFrozenVersionWhileTheDraftMovesOn() {
        // Arrange
        String id = create("GET", "customers", "public");
        save(id, "GET", "customers", "public", List.of(param("tier", "query", "string", false)), """
                select id, name, joined, balance, active from customers
                <where><if test="tier != null">tier = #{tier}</if></where>
                order by id limit 3""", null);

        // Act: not served before publish
        call("GET", "customers", null).expectProblem(404, "not_found");
        var published = admin.post(base() + "/" + id + ":publish", Map.of("note", "first")).expect(200).json();
        JsonNode body = call("GET", "customers?tier=gold", null).expect(200).json();

        // Assert
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("meta").path("version").asInt()).isEqualTo(1);
        assertThat(body.path("data").findValuesAsText("name")).containsExactly("customer 03", "customer 06", "customer 09");
        JsonNode first = body.path("data").get(0);
        assertThat(first.path("joined").asText()).isEqualTo("2026-01-04");
        assertThat(first.path("balance").decimalValue()).isEqualByComparingTo("300.25");
        assertThat(first.path("active").isBoolean()).isTrue();

        // Act: change the draft; callers still get v1 until the next publish
        save(id, "GET", "customers", "public", List.of(param("tier", "query", "string", false)),
                "select id from customers order by id desc limit 1", null);
        JsonNode stillV1 = call("GET", "customers", null).expect(200).json();
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);
        JsonNode v2 = call("GET", "customers", null).expect(200).json();

        // Assert
        assertThat(stillV1.path("meta").path("version").asInt()).isEqualTo(1);
        assertThat(v2.path("meta").path("version").asInt()).isEqualTo(2);
        assertThat(v2.path("data").get(0).path("id").asInt()).isEqualTo(30);
    }

    @Test
    void pathParametersTypesAndUnknownParameters() {
        // Arrange
        String id = create("GET", "customers/{id}", "public");
        save(id, "GET", "customers/{id}", "public", List.of(param("id", "path", "bigint", true)),
                "select id, name from customers where id = #{id}", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act + Assert
        assertThat(call("GET", "customers/7", null).expect(200).json().path("data").get(0).path("name").asText()).isEqualTo("customer 07");
        call("GET", "customers/abc", null).expectProblem(400, "invalid_param");
        call("GET", "customers/7?debug=1", null).expectProblem(400, "invalid_param");
    }

    @Test
    void paginationReturnsPagesAndTotals() {
        // Arrange
        String id = create("GET", "page", "public");
        save(id, "GET", "page", "public", List.of(), "select id from customers order by id",
                Map.of("enabled", true, "defaultSize", 10, "maxSize", 20, "count", true));
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act
        JsonNode p3 = call("GET", "page?page=3&size=12", null).expect(200).json();

        // Assert
        assertThat(p3.path("data").findValuesAsText("id")).containsExactly("25", "26", "27", "28", "29", "30");
        assertThat(p3.path("meta").path("total").asInt()).isEqualTo(30);
        assertThat(p3.path("meta").path("hasNext").asBoolean()).isFalse();
        call("GET", "page?size=50", null).expectProblem(400, "invalid_param");
    }

    @Test
    void postWithJsonBodyWritesInATransaction() {
        // Arrange
        String id = create("POST", "notes", "public");
        save(id, "POST", "notes", "public", List.of(param("customerId", "body", "bigint", true), param("body", "body", "string", true)),
                "insert into notes (customer_id, body) values (#{customerId}, #{body})", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act
        JsonNode written = call("POST", "notes", Map.of("customerId", 1, "body", "called back")).expect(200).json();

        // Assert
        assertThat(written.path("data").path("affectedRows").asInt()).isEqualTo(1);
        call("POST", "notes", Map.of("customerId", 1)).expectProblem(400, "invalid_param");
    }

    @Test
    void validationBlocksPublishingBrokenOrUnsafeDrafts() {
        // Arrange
        String id = create("GET", "broken", "public");
        save(id, "GET", "broken", "public", List.of(), "delete from customers where id = #{id}", null);

        // Act
        JsonNode validation = admin.post(base() + "/" + id + ":validate", null).expect(200).json();

        // Assert
        assertThat(validation.path("ok").asBoolean()).isFalse();
        assertThat(validation.path("findings").findValuesAsText("code")).contains("undeclared_param", "write_in_get");
        admin.post(base() + "/" + id + ":publish", Map.of()).expectProblem(409, "validation_failed");
    }

    @Test
    void validationReportsOutputColumnsUsedLaterForDocs() {
        // Arrange
        String id = create("GET", "cols", "public");
        save(id, "GET", "cols", "public", List.of(), "select id, name, balance from customers", null);

        // Act
        JsonNode validation = admin.post(base() + "/" + id + ":validate", null).expect(200).json();

        // Assert
        assertThat(validation.path("ok").asBoolean()).isTrue();
        assertThat(validation.path("outputColumns").findValuesAsText("name")).containsExactly("id", "name", "balance");
    }

    @Test
    void previewRunsTheDraftButNeverCommits() {
        // Arrange
        String id = create("POST", "preview-write", "public");
        Map<String, Object> definition = Map.of("params", List.of(param("body", "body", "string", true)),
                "task", Map.of("type", "sql", "datasourceId", datasourceId, "transaction", false,
                        "statements", List.of(Map.of("id", "main", "template", "insert into notes (customer_id, body) values (2, #{body})"))));

        // Act
        JsonNode preview = admin.post(base() + "/" + id + "/preview", Map.of("method", "POST", "path", "preview-write",
                "definition", definition, "params", Map.of("body", "preview-marker"))).expect(200).json();
        String countId = create("GET", "count-marker", "public");
        save(countId, "GET", "count-marker", "public", List.of(), "select count(*) as n from notes where body = 'preview-marker'", null);
        admin.post(base() + "/" + countId + ":publish", Map.of()).expect(200);

        // Assert
        assertThat(preview.path("affectedRows").asInt()).isEqualTo(1);
        assertThat(preview.path("executed").get(0).path("sql").asText()).contains("values (2, ?)");
        assertThat(call("GET", "count-marker", null).json().path("data").get(0).path("n").asInt()).isZero();
    }

    @Test
    void previewShowsTheDatabaseErrorToTheAuthor() {
        // Arrange
        String id = create("GET", "bad-sql", "public");
        Map<String, Object> definition = Map.of("task", Map.of("type", "sql", "datasourceId", datasourceId, "transaction", false,
                "statements", List.of(Map.of("id", "main", "template", "select nope from customers"))));

        // Act + Assert
        var response = admin.post(base() + "/" + id + "/preview", Map.of("method", "GET", "path", "bad-sql", "definition", definition));
        response.expectProblem(400, "query_failed");
        assertThat(response.json().path("detail").asText()).contains("nope");
    }

    @Test
    void callersNeverSeeDatabaseErrors() {
        // Arrange: valid SQL at publish, broken afterwards by a schema change
        String id = create("GET", "fragile", "public");
        save(id, "GET", "fragile", "public", List.of(), "select id from customers where tier = 'gold' and 1 / (id - id) = 1", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act
        var response = call("GET", "fragile", null);

        // Assert
        response.expectProblem(500, "query_failed");
        assertThat(response.body()).doesNotContain("division").contains("requestId");
    }

    @Test
    void lifecycleDisableTrashRestoreRollback() {
        // Arrange
        String id = create("GET", "life", "public");
        save(id, "GET", "life", "public", List.of(), "select 1 as v", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);
        save(id, "GET", "life", "public", List.of(), "select 2 as v", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act + Assert: rollback serves v1 again without touching the draft
        admin.post(base() + "/" + id + "/versions/1:rollback", null).expect(200);
        assertThat(call("GET", "life", null).json().path("data").get(0).path("v").asInt()).isEqualTo(1);
        assertThat(admin.get(base() + "/" + id + "/versions").json().findValuesAsText("version")).containsExactly("2", "1");

        // Act + Assert: live cannot be trashed; disabled stops serving; restore comes back disabled
        admin.post(base() + "/" + id + ":trash", null).expectProblem(409, "endpoint_live");
        admin.patch(base() + "/" + id, Map.of("name", "life", "method", "GET", "path", "life2", "access", "public",
                "definition", Map.of())).expectProblem(409, "route_locked");
        admin.post(base() + "/" + id + ":disable", null).expect(200);
        call("GET", "life", null).expectProblem(404, "not_found");
        admin.post(base() + "/" + id + ":trash", null).expect(200);
        assertThat(admin.post(base() + "/" + id + ":restore", null).expect(200).json().path("state").asText()).isEqualTo("disabled");
        admin.post(base() + "/" + id + ":enable", null).expect(200);
        call("GET", "life", null).expect(200);
    }

    @Test
    void privateEndpointsNeedATokenAndViewersCannotAuthor() {
        // Arrange
        String id = create("GET", "secret", "private");
        save(id, "GET", "secret", "private", List.of(), "select 1 as v", null);
        admin.post(base() + "/" + id + ":publish", Map.of()).expect(200);

        // Act + Assert
        call("GET", "secret", null).expectProblem(401, "unauthenticated");
        viewer.get(base()).expect(200);
        viewer.post(base() + "/" + id + ":disable", null).expectProblem(403, "forbidden");
        viewer.post(base(), Map.of("name", "x", "method", "GET", "path", "x", "access", "public", "datasourceId", datasourceId))
                .expectProblem(403, "forbidden");
    }

    @Test
    void routesAreUniquePerWorkspaceAndDuplicatesGetAFreePath() {
        // Arrange
        String id = create("GET", "dup", "public");

        // Act + Assert
        admin.post(base(), Map.of("name", "again", "method", "GET", "path", "dup", "access", "public", "datasourceId", datasourceId))
                .expectProblem(409, "route_taken");
        var copy = admin.post(base() + "/" + id + ":duplicate", null).expect(201).json();
        var copy2 = admin.post(base() + "/" + id + ":duplicate", null).expect(201).json();
        assertThat(copy.path("summary").path("path").asText()).isEqualTo("dup-copy");
        assertThat(copy2.path("summary").path("path").asText()).isEqualTo("dup-copy-2");
    }
}
