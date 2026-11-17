package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.VaultSecrets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** vault:PATH#KEY references against a real Vault in dev mode (spec §4.5). */
class VaultIntegrationTest extends AppTestSupport {

    @SuppressWarnings("resource")
    static final GenericContainer<?> VAULT = new GenericContainer<>("hashicorp/vault:1.17.6")
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
            .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withEnv("SKIP_SETCAP", "true")
            .withExposedPorts(8200)
            .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

    static {
        VAULT.start();
    }

    @DynamicPropertySource
    static void vault(DynamicPropertyRegistry registry) {
        registry.add("opendbapi.vault.address", VaultIntegrationTest::address);
        registry.add("opendbapi.vault.token", () -> "root");
    }

    private static String address() {
        return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
    }

    private static String vault(String method, String path, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(address() + "/v1/" + path)).header("X-Vault-Token", "root");
        r = body == null ? r.method(method, HttpRequest.BodyPublishers.noBody()) : r.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HttpClient.newHttpClient().send(r.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isLessThan(300);
        return response.body();
    }

    private ApiClient admin;
    private String ws;

    @BeforeEach
    void arrange() throws Exception {
        admin = signedInAdmin();
        ws = "va-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Vault " + ws)).expect(201);
        vault("POST", "secret/data/opendbapi/" + ws + "/db", "{\"data\":{\"password\":\"" + POSTGRES.getPassword() + "\"}}");
        vault("POST", "secret/data/opendbapi/other/db", "{\"data\":{\"password\":\"someone-elses\"}}");
    }

    private Map<String, Object> datasource(String password) {
        return new HashMap<>(Map.of("name", "main", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", password, "properties", Map.of(), "readOnly", true, "pool", Map.of()));
    }

    @Test
    void aDatasourceReadsItsPasswordFromItsOwnVaultPathOnly() {
        // Act
        var created = admin.post("/admin/api/ws/" + ws + "/datasources", datasource("vault:secret/opendbapi/" + ws + "/db#password"))
                .expect(201).json();
        boolean connects = admin.post("/admin/api/ws/" + ws + "/datasources/" + created.path("id").asText() + ":test", Map.of())
                .expect(200).json().path("ok").asBoolean();
        var otherWorkspace = admin.post("/admin/api/ws/" + ws + "/datasources",
                datasource("vault:secret/opendbapi/other/db#password"));
        var missingKey = admin.post("/admin/api/ws/" + ws + "/datasources/test",
                Map.of("settings", datasource("vault:secret/opendbapi/" + ws + "/db#nope")));
        String exported = admin.get("/admin/api/ws/" + ws + "/export").expect(200).body();

        // Assert
        assertThat(created.path("passwordEnv").asText()).isEqualTo("vault:secret/opendbapi/" + ws + "/db#password");
        assertThat(connects).isTrue();
        otherWorkspace.expectProblem(400, "secret_reference_invalid");
        missingKey.expectProblem(400, "secret_unavailable");
        assertThat(exported).contains("passwordRef: vault:secret/opendbapi/" + ws + "/db#password");
    }

    @Test
    void appRoleLoginReadsSecretsAndPathsStayConfined() throws Exception {
        // Arrange
        vault("POST", "sys/auth/approle", "{\"type\":\"approle\"}");
        vault("PUT", "sys/policies/acl/opendbapi", "{\"policy\":\"path \\\"secret/data/opendbapi/*\\\" { capabilities = [\\\"read\\\"] }\"}");
        vault("POST", "auth/approle/role/opendbapi", "{\"token_policies\":\"opendbapi\",\"token_ttl\":\"60s\"}");
        String roleId = ApiClient.JSON.readTree(vault("GET", "auth/approle/role/opendbapi/role-id", null)).path("data").path("role_id").asText();
        String secretId = ApiClient.JSON.readTree(vault("POST", "auth/approle/role/opendbapi/secret-id", null))
                .path("data").path("secret_id").asText();
        var secrets = new VaultSecrets(address(), "approle", "", "", 2, "secret/opendbapi/{workspace}/", "secret/opendbapi/system/", 0,
                "", "", roleId, secretId, "/nonexistent");

        // Act
        String value = secrets.read("vault:secret/opendbapi/" + ws + "/db#password", ws);

        // Assert
        assertThat(value).isEqualTo(POSTGRES.getPassword());
        assertThatThrownBy(() -> secrets.read("vault:secret/opendbapi/other/db#password", ws))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("outside what this workspace may read");
        assertThatThrownBy(() -> secrets.read("vault:secret/opendbapi/../system/x#y", ws)).isInstanceOf(ApiProblem.class);
    }
}
