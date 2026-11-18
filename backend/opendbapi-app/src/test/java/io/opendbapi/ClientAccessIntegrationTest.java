package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Service accounts, client credentials, tokens and grants, end to end (spec §8.1). */
class ClientAccessIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String endpointId;
    private String groupId;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "cl-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Clients " + ws)).expect(201);
        groupId = admin.post(ws("/groups"), Map.of("name", "reports")).expect(201).json().path("id").asText();
        endpointId = privateEndpoint("whoami", groupId);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    /** A published private endpoint that needs no datasource rows: select 1. */
    private String privateEndpoint(String path, String group) {
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta-" + path, "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        Map<String, Object> create = new HashMap<>(Map.of("name", path, "method", "GET", "path", path, "access", "private",
                "datasourceId", dsId));
        if (group != null) {
            create.put("groupId", group);
        }
        String id = admin.post(ws("/endpoints"), create).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", path, "method", "GET", "path", path, "access", "private",
                "groupId", group == null ? "" : group,
                "definition", Map.of("params", List.of(), "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                        "statements", List.of(Map.of("id", "main", "template", "select 1 as ok")))))).expect(200);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        return id;
    }

    private record Credentials(String saId, String clientRowId, String clientId, String secret) {
    }

    private Credentials newClient(String name) {
        String saId = admin.post(ws("/service-accounts"), Map.of("name", name, "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();
        JsonNode created = admin.post(ws("/service-accounts/" + saId + "/clients"), Map.of()).expect(201).json();
        return new Credentials(saId, created.path("client").path("id").asText(), created.path("client").path("clientId").asText(),
                created.path("clientSecret").asText());
    }

    private ApiClient.Response token(Credentials c) {
        String basic = Base64.getEncoder().encodeToString((c.clientId() + ":" + c.secret()).getBytes(StandardCharsets.UTF_8));
        return client().header("Authorization", "Basic " + basic).form("/oauth2/token", Map.of("grant_type", "client_credentials"));
    }

    private ApiClient.Response call(String path, String token) {
        return client().header("Authorization", token == null ? null : "Bearer " + token).get("/api/" + ws + "/" + path);
    }

    @Test
    void grantedClientGetsATokenAndCallsThePrivateEndpoint() {
        // Arrange
        Credentials c = newClient("billing");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId))
                .expect(204);

        // Act
        JsonNode issued = token(c).expect(200).json();
        JsonNode body = call("whoami", issued.path("access_token").asText()).expect(200).json();

        // Assert
        assertThat(issued.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(issued.path("expires_in").asInt()).isEqualTo(900);
        assertThat(body.path("data").get(0).path("ok").asInt()).isEqualTo(1);
    }

    @Test
    void groupGrantCoversEveryEndpointOfTheGroupOnly() {
        // Arrange
        Credentials c = newClient("group-reader");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "group", "resourceId", groupId)).expect(204);
        privateEndpoint("outside", null);
        String token = token(c).json().path("access_token").asText();

        // Act + Assert
        call("whoami", token).expect(200);
        call("outside", token).expectProblem(403, "not_granted");
        assertThat(admin.get(ws("/endpoints/" + endpointId + "/access")).json().findValuesAsText("via")).containsExactly("group");
    }

    @Test
    void movingAnEndpointBetweenGroupsChangesGroupAccessAtOnceWithoutTouchingTheDraft() {
        // Arrange
        Credentials c = newClient("mover");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "group", "resourceId", groupId)).expect(204);
        String token = token(c).json().path("access_token").asText();
        String other = admin.post(ws("/groups"), Map.of("name", "other")).expect(201).json().path("id").asText();
        call("whoami", token).expect(200);
        Map<String, Object> noGroup = new HashMap<>();
        noGroup.put("groupId", null);

        // Act + Assert: out of the granted group, the call is refused
        JsonNode moved = admin.put(ws("/endpoints/" + endpointId + "/group"), Map.of("groupId", other)).expect(200).json();
        assertThat(moved.path("groupId").asText()).isEqualTo(other);
        assertThat(moved.path("dirty").asBoolean()).isFalse();
        assertThat(moved.path("state").asText()).isEqualTo("live");
        call("whoami", token).expectProblem(403, "not_granted");

        admin.put(ws("/endpoints/" + endpointId + "/group"), noGroup).expect(200);
        assertThat(admin.get(ws("/endpoints/" + endpointId)).json().path("summary").path("groupId").isNull()).isTrue();

        admin.put(ws("/endpoints/" + endpointId + "/group"), Map.of("groupId", groupId)).expect(200);
        call("whoami", token).expect(200);
        assertThat(admin.get(ws("/audit")).json().toString()).contains("endpoint.move");
    }

    @Test
    void movingIntoAnUnknownGroupIsRefused() {
        // Act + Assert
        admin.put(ws("/endpoints/" + endpointId + "/group"), Map.of("groupId", UUID.randomUUID().toString()))
                .expectProblem(404, "group_not_found");
    }

    @Test
    void deletingAGroupUngroupsItsEndpointsAndRevokesGrantsOnIt() {
        // Arrange
        Credentials c = newClient("folder-reader");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "group", "resourceId", groupId)).expect(204);
        JsonNode before = admin.get(ws("/groups")).json().get(0);
        assertThat(before.path("endpointCount").asInt()).isEqualTo(1);
        assertThat(before.path("grantCount").asInt()).isEqualTo(1);
        String token = token(c).json().path("access_token").asText();
        call("whoami", token).expect(200);

        // Act
        admin.delete(ws("/groups/" + groupId)).expect(204);

        // Assert
        assertThat(admin.get(ws("/groups")).json()).isEmpty();
        assertThat(admin.get(ws("/endpoints/" + endpointId)).json().path("summary").path("groupId").isNull()).isTrue();
        assertThat(admin.get(ws("/service-accounts/" + c.saId())).json().path("grants")).isEmpty();
        call("whoami", token).expectProblem(403, "not_granted");
    }

    @Test
    void clientCredentialsAlsoWorkAsFormFieldsAndWrongOnesAreRefused() {
        // Arrange
        Credentials c = newClient("form-auth");

        // Act
        var ok = client().form("/oauth2/token", Map.of("grant_type", "client_credentials", "client_id", c.clientId(),
                "client_secret", c.secret()));
        var wrongSecret = client().form("/oauth2/token", Map.of("grant_type", "client_credentials", "client_id", c.clientId(),
                "client_secret", "ods_nope"));
        var unknown = client().form("/oauth2/token", Map.of("grant_type", "client_credentials", "client_id", "odc_unknown",
                "client_secret", "x"));
        var wrongGrant = client().form("/oauth2/token", Map.of("grant_type", "password", "client_id", c.clientId(),
                "client_secret", c.secret()));

        // Assert
        ok.expect(200);
        assertThat(wrongSecret.status()).isEqualTo(401);
        assertThat(wrongSecret.json().path("error").asText()).isEqualTo("invalid_client");
        assertThat(unknown.json().path("error").asText()).isEqualTo("invalid_client");
        assertThat(wrongGrant.json().path("error").asText()).isEqualTo("unsupported_grant_type");
    }

    @Test
    void secretIsShownOnceAndNeverListed() {
        // Arrange
        Credentials c = newClient("secretive");

        // Act
        String detail = admin.get(ws("/service-accounts/" + c.saId())).expect(200).body();

        // Assert
        assertThat(c.secret()).startsWith("ods_");
        assertThat(detail).contains(c.clientId()).doesNotContain(c.secret()).doesNotContain("sha256:");
    }

    @Test
    void missingBadOrForeignTokensAreRefused() {
        // Arrange
        Credentials c = newClient("tokens");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId))
                .expect(204);
        String token = token(c).json().path("access_token").asText();
        String otherWs = "cl-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", otherWs, "name", "Other " + otherWs)).expect(201);

        // Act
        var missing = call("whoami", null);
        var tampered = call("whoami", token.substring(0, token.length() - 4) + "AAAA");

        // Assert
        missing.expectProblem(401, "unauthenticated");
        tampered.expectProblem(401, "invalid_token");
        client().header("Authorization", "Bearer " + token).get("/api/" + otherWs + "/whoami").expectProblem(404, "not_found");
    }

    @Test
    void revokingTheClientOrDisablingTheAccountStopsTokensAtOnce() {
        // Arrange
        Credentials c = newClient("revocable");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId))
                .expect(204);
        String token = token(c).json().path("access_token").asText();
        call("whoami", token).expect(200);

        // Act + Assert: disable, then enable again
        admin.patch(ws("/service-accounts/" + c.saId()), Map.of("name", "revocable", "disabled", true)).expect(200);
        call("whoami", token).expectProblem(401, "invalid_token");
        token(c).expect(401);
        admin.patch(ws("/service-accounts/" + c.saId()), Map.of("name", "revocable", "disabled", false)).expect(200);
        call("whoami", token).expect(200);

        // Act + Assert: revoke the client
        admin.post(ws("/service-accounts/" + c.saId() + "/clients/" + c.clientRowId() + ":revoke"), null).expect(204);
        call("whoami", token).expectProblem(401, "invalid_token");
        token(c).expect(401);
    }

    @Test
    void rotatingTheSecretKeepsTheClientIdAndRetiresTheOldSecret() {
        // Arrange
        Credentials c = newClient("rotating");

        // Act
        JsonNode rotated = admin.post(ws("/service-accounts/" + c.saId() + "/clients/" + c.clientRowId() + ":rotate"), null)
                .expect(200).json();
        Credentials fresh = new Credentials(c.saId(), c.clientRowId(), c.clientId(), rotated.path("clientSecret").asText());

        // Assert
        assertThat(rotated.path("client").path("clientId").asText()).isEqualTo(c.clientId());
        token(c).expect(401);
        token(fresh).expect(200);
    }

    @Test
    void removingTheGrantForbidsTheCall() {
        // Arrange
        Credentials c = newClient("ungranted");
        admin.put(ws("/service-accounts/" + c.saId() + "/grants"), Map.of("resourceType", "endpoint", "resourceId", endpointId))
                .expect(204);
        String token = token(c).json().path("access_token").asText();

        // Act
        admin.delete(ws("/service-accounts/" + c.saId() + "/grants/endpoint/" + endpointId)).expect(204);

        // Assert
        call("whoami", token).expectProblem(403, "not_granted");
    }

    @Test
    void onlyWorkspaceAdminsManageServiceAccounts() {
        // Arrange
        String name = "author-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!", "systemAdmin", false))
                .expect(201).json().path("id").asText();
        admin.post(ws("/members"), Map.of("userId", userId, "role", "author")).expect(201);
        ApiClient author = client();
        author.login(name, name + "-password!").expect(204);
        Credentials c = newClient("guarded");

        // Act + Assert
        author.get(ws("/service-accounts")).expect(200);
        author.post(ws("/service-accounts"), Map.of("name", "mine", "disabled", false)).expectProblem(403, "forbidden");
        author.post(ws("/service-accounts/" + c.saId() + "/clients"), Map.of()).expectProblem(403, "forbidden");
    }

    @Test
    void jwksPublishesTheVerificationKey() {
        // Act
        JsonNode jwks = client().get("/oauth2/jwks").expect(200).json();

        // Assert
        assertThat(jwks.path("keys").get(0).path("kty").asText()).isEqualTo("RSA");
        assertThat(jwks.path("keys").get(0).has("d")).isFalse();
    }
}
