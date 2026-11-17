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

/** Column masks and row filters on the serving path (spec §12). */
class DataPolicyIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String table;
    private String dsId;

    @BeforeEach
    void arrange() throws Exception {
        admin = signedInAdmin();
        ws = "dp-" + UUID.randomUUID().toString().substring(0, 8);
        table = "customers_" + ws.substring(3);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Policies " + ws)).expect(201);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create table " + table + " (id int primary key, tenant text, phone text, email text)");
            s.execute("insert into " + table + " values (1, 'acme', '0901234123', 'ann@acme.test'),"
                    + " (2, 'acme', '0907654321', 'bob@acme.test'), (3, 'globex', '0912345678', 'cy@globex.test')");
        }
        Map<String, Object> ds = new HashMap<>(Map.of("name", "crm", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    /** A draft endpoint; publish separately so policies can be added first. */
    private String endpoint(String path, String access, String sql) {
        String id = admin.post(ws("/endpoints"), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", access,
                "definition", Map.of("params", List.of(), "streaming", Map.of("allowed", true),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", sql)))))).expect(200);
        return id;
    }

    private void publish(String endpointId) {
        admin.post(ws("/endpoints/" + endpointId + ":publish"), Map.of()).expect(200);
    }

    private String serviceAccount(String name, Map<String, Object> attributes, String endpointId) {
        String saId = admin.post(ws("/service-accounts"), Map.of("name", name, "disabled", false, "attributes", attributes))
                .expect(201).json().path("summary").path("id").asText();
        admin.put(ws("/service-accounts/" + saId + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId))
                .expect(204);
        return saId;
    }

    private String token(String saId) {
        JsonNode created = admin.post(ws("/service-accounts/" + saId + "/clients"), Map.of()).expect(201).json();
        return client().form("/oauth2/token", Map.of("grant_type", "client_credentials",
                "client_id", created.path("client").path("clientId").asText(),
                "client_secret", created.path("clientSecret").asText())).expect(200).json().path("access_token").asText();
    }

    private ApiClient.Response call(String path, String token) {
        return client().header("Authorization", token == null ? null : "Bearer " + token).get("/api/" + ws + "/" + path);
    }

    private static List<String> column(JsonNode body, String name) {
        List<String> out = new ArrayList<>();
        body.path("data").forEach(r -> out.add(r.path(name).asText()));
        return out;
    }

    private Map<String, Object> rowFilter(String endpointId, List<String> exempt) {
        return Map.of("kind", "row_filter", "endpointId", endpointId, "column", "tenant", "attribute", "tenant",
                "exemptServiceAccounts", exempt);
    }

    @Test
    void rowFilterReturnsOnlyTheCallersRows() {
        // Arrange
        String ep = endpoint("customers", "private", "select id, tenant, email from " + table + " order by id");
        String acme = serviceAccount("acme-app", Map.of("tenant", "acme"), ep);
        String both = serviceAccount("both-app", Map.of("tenant", List.of("acme", "globex")), ep);
        String none = serviceAccount("no-tenant", Map.of(), ep);
        String ops = serviceAccount("ops", Map.of(), ep);
        admin.post(ws("/data-policies"), rowFilter(ep, List.of(ops))).expect(201);
        publish(ep);

        // Act
        JsonNode acmeRows = call("customers", token(acme)).expect(200).json();
        JsonNode bothRows = call("customers", token(both)).expect(200).json();
        ApiClient.Response missing = call("customers", token(none));
        JsonNode opsRows = call("customers", token(ops)).expect(200).json();

        // Assert
        assertThat(column(acmeRows, "id")).containsExactly("1", "2");
        assertThat(column(bothRows, "id")).containsExactly("1", "2", "3");
        missing.expectProblem(403, "missing_attribute");
        assertThat(column(opsRows, "id")).containsExactly("1", "2", "3");
    }

    @Test
    void rowFilterAlsoAppliesToPagesCountsAndStreams() {
        // Arrange
        String ep = endpoint("paged", "private", "select id, tenant from " + table + " order by id");
        admin.patch(ws("/endpoints/" + ep), Map.of("name", "paged", "method", "GET", "path", "paged", "access", "private",
                "definition", Map.of("params", List.of(), "streaming", Map.of("allowed", true),
                        "pagination", Map.of("enabled", true, "defaultSize", 1, "maxSize", 10, "count", true),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", "select id, tenant from " + table + " order by id"))))))
                .expect(200);
        String acme = serviceAccount("acme-pages", Map.of("tenant", "acme"), ep);
        admin.post(ws("/data-policies"), rowFilter(ep, List.of())).expect(201);
        publish(ep);
        String token = token(acme);

        // Act
        JsonNode page2 = call("paged?page=2", token).expect(200).json();
        String streamed = call("paged?format=ndjson", token).expect(200).body();

        // Assert
        assertThat(column(page2, "id")).containsExactly("2");
        assertThat(page2.path("meta").path("total").asInt()).isEqualTo(2);
        assertThat(streamed).contains("\"id\":1").contains("\"id\":2").doesNotContain("\"id\":3");
    }

    @Test
    void masksFollowAliasesAndSpareExemptAccounts() {
        // Arrange: a datasource mask on the table column, an endpoint mask on a result column
        String ep = endpoint("contacts", "private", "select c.id, c.email as contact, c.phone from " + table + " c order by c.id");
        String app = serviceAccount("app", Map.of(), ep);
        String support = serviceAccount("support", Map.of(), ep);
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "datasourceId", dsId, "column", table + ".email",
                "mask", Map.of("type", "email"))).expect(201);
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "endpointId", ep, "column", "phone",
                "mask", Map.of("type", "partial", "keepStart", 0, "keepEnd", 3), "exemptServiceAccounts", List.of(support)))
                .expect(201);
        publish(ep);

        // Act
        JsonNode masked = call("contacts", token(app)).expect(200).json();
        JsonNode exempt = call("contacts", token(support)).expect(200).json();
        String streamed = call("contacts?format=ndjson", token(app)).expect(200).body();

        // Assert
        assertThat(column(masked, "contact")).containsExactly("a***@acme.test", "b***@acme.test", "c***@globex.test");
        assertThat(column(masked, "phone")).containsExactly("*******123", "*******321", "*******678");
        assertThat(column(exempt, "contact")).containsExactly("a***@acme.test", "b***@acme.test", "c***@globex.test");
        assertThat(column(exempt, "phone")).containsExactly("0901234123", "0907654321", "0912345678");
        assertThat(streamed).contains("a***@acme.test").doesNotContain("ann@acme.test").doesNotContain("0901234123");
    }

    @Test
    void publishRefusesRowFiltersThatCannotWork() {
        // Arrange
        String pub = endpoint("open", "public", "select id, tenant from " + table);
        String noColumn = endpoint("narrow", "private", "select id from " + table);
        admin.post(ws("/data-policies"), rowFilter(pub, List.of())).expect(201);
        admin.post(ws("/data-policies"), rowFilter(noColumn, List.of())).expect(201);

        // Act
        JsonNode publicFindings = admin.post(ws("/endpoints/" + pub + ":validate"), Map.of()).expect(200).json();
        JsonNode missingFindings = admin.post(ws("/endpoints/" + noColumn + ":validate"), Map.of()).expect(200).json();

        // Assert
        assertThat(publicFindings.findValuesAsText("code")).contains("row_filter_public");
        assertThat(missingFindings.findValuesAsText("code")).contains("row_filter_column");
        admin.post(ws("/endpoints/" + pub + ":publish"), Map.of()).expectProblem(409, "validation_failed");
    }

    @Test
    void policiesAreValidatedListedAndRemovable() {
        // Arrange
        String ep = endpoint("listed", "private", "select id, tenant from " + table);

        // Act
        admin.post(ws("/data-policies"), Map.of("kind", "row_filter", "endpointId", ep, "column", "tenant; drop", "attribute", "t"))
                .expectProblem(400, "invalid_policy");
        admin.post(ws("/data-policies"), Map.of("kind", "mask", "datasourceId", dsId, "column", "email",
                "mask", Map.of("type", "rot13"))).expectProblem(400, "invalid_policy");
        String id = admin.post(ws("/data-policies"), rowFilter(ep, List.of())).expect(201).json().path("id").asText();
        admin.post(ws("/data-policies"), rowFilter(ep, List.of())).expectProblem(409, "row_filter_exists");
        JsonNode listed = admin.get(ws("/data-policies?endpointId=" + ep)).expect(200).json();
        admin.delete(ws("/data-policies/" + id)).expect(204);

        // Assert
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).toString()).doesNotContain("salt");
        assertThat(admin.get(ws("/data-policies")).json()).isEmpty();
    }

    @Test
    void serviceAccountAttributesCanBeChanged() {
        // Arrange
        String saId = admin.post(ws("/service-accounts"), Map.of("name", "attrs", "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();

        // Act
        admin.patch(ws("/service-accounts/" + saId), Map.of("name", "attrs", "disabled", false,
                "attributes", Map.of("tenant", "acme", "regions", List.of("north", "south")))).expect(200);
        admin.patch(ws("/service-accounts/" + saId), Map.of("name", "attrs", "disabled", false,
                "attributes", Map.of("bad name", 1))).expectProblem(400, "invalid_attributes");
        JsonNode kept = admin.patch(ws("/service-accounts/" + saId), Map.of("name", "attrs2", "disabled", false)).expect(200).json();

        // Assert
        assertThat(kept.path("summary").path("attributes").path("tenant").asText()).isEqualTo("acme");
        assertThat(kept.path("summary").path("attributes").path("regions")).hasSize(2);
    }
}
