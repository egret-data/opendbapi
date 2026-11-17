package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.client.RequestSecurity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Signed requests (spec §8.2) and encrypted responses (spec §8.3). */
class ClientSecurityIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String saId;
    private String clientRowId;
    private String token;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "cs-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Security " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post(ws("/datasources"), ds).expect(201).json().path("id").asText();
        String id = admin.post(ws("/endpoints"), Map.of("name", "echo", "method", "GET", "path", "echo", "access", "private",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch(ws("/endpoints/" + id), Map.of("name", "echo", "method", "GET", "path", "echo", "access", "private",
                "definition", Map.of("params", List.of(Map.of("name", "word", "in", "query", "type", "string", "required", true)),
                        "streaming", Map.of("allowed", true),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", "select #{word}::text as word")))))).expect(200);
        admin.post(ws("/endpoints/" + id + ":publish"), Map.of()).expect(200);
        saId = admin.post(ws("/service-accounts"), Map.of("name", "partner", "disabled", false)).expect(201)
                .json().path("summary").path("id").asText();
        admin.put(ws("/service-accounts/" + saId + "/grants"), Map.of("resourceType", "endpoint", "resourceId", id)).expect(204);
        JsonNode created = admin.post(ws("/service-accounts/" + saId + "/clients"), Map.of()).expect(201).json();
        clientRowId = created.path("client").path("id").asText();
        token = client().form("/oauth2/token", Map.of("grant_type", "client_credentials",
                "client_id", created.path("client").path("clientId").asText(),
                "client_secret", created.path("clientSecret").asText())).expect(200).json().path("access_token").asText();
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    private HttpResponse<String> get(String query, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/echo?" + query))
                .header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        return HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> signed(String key, String word, long timestamp, String nonce) {
        String canonical = RequestSecurity.canonical("GET", "/api/" + ws + "/echo", Map.of("word", List.of(word)), new byte[0],
                Long.toString(timestamp), nonce);
        return Map.of("X-Timestamp", Long.toString(timestamp), "X-Nonce", nonce, "X-Signature", RequestSecurity.sign(key, canonical));
    }

    @Test
    void signedClientsMustSignEveryRequestOnce() throws Exception {
        // Arrange
        String key = admin.put(ws("/service-accounts/" + saId + "/clients/" + clientRowId + "/security"),
                Map.of("hmacRequired", true, "rotateSigningKey", false, "responseEncrypt", false)).expect(200).json().path("signingKey").asText();
        long now = System.currentTimeMillis();
        Map<String, String> good = signed(key, "hello", now, "n-1");

        // Act
        HttpResponse<String> unsigned = get("word=hello", Map.of());
        HttpResponse<String> ok = get("word=hello", good);
        HttpResponse<String> replay = get("word=hello", good);
        HttpResponse<String> tampered = get("word=other", signed(key, "hello", now, "n-2"));
        HttpResponse<String> stale = get("word=hello", signed(key, "hello", now - 600_000, "n-3"));

        // Assert
        assertThat(key).startsWith("odh_");
        assertThat(unsigned.statusCode()).isEqualTo(401);
        assertThat(unsigned.body()).contains("bad_signature");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("already used");
        // The nonce lives in PostgreSQL, so every instance refuses the replay.
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var rs = c.createStatement().executeQuery("select count(*) from opendbapi.request_nonce where nonce = 'n-1'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        assertThat(tampered.statusCode()).isEqualTo(401);
        assertThat(stale.statusCode()).isEqualTo(401);
        String listed = admin.get(ws("/service-accounts/" + saId)).body();
        assertThat(listed).contains("\"hmacRequired\":true").doesNotContain(key);
    }

    @Test
    void encryptedClientsGetResponsesOnlyTheirKeyOpens() throws Exception {
        // Arrange
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        admin.put(ws("/service-accounts/" + saId + "/clients/" + clientRowId + "/security"),
                Map.of("hmacRequired", false, "rotateSigningKey", false, "responseEncrypt", true, "publicKey", pem)).expect(200);

        // Act
        HttpResponse<String> response = get("word=secret-word", Map.of());
        HttpResponse<String> streamed = get("word=x&format=ndjson", Map.of());
        JsonNode sealed = ApiClient.JSON.readTree(response.body());
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, pair.getPrivate(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] aes = rsa.doFinal(Base64.getDecoder().decode(sealed.path("encryptedKey").asText()));
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(sealed.path("iv").asText())));
        JsonNode envelope = ApiClient.JSON.readTree(gcm.doFinal(Base64.getDecoder().decode(sealed.path("data").asText())));

        // Assert
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("secret-word");
        assertThat(envelope.path("data").get(0).path("word").asText()).isEqualTo("secret-word");
        assertThat(streamed.statusCode()).isEqualTo(406);
    }

    @Test
    void weakOrMissingKeysAreRefused() throws Exception {
        // Arrange
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String weak = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPublic()
                .getEncoded()) + "\n-----END PUBLIC KEY-----";

        // Act + Assert
        admin.put(ws("/service-accounts/" + saId + "/clients/" + clientRowId + "/security"),
                Map.of("hmacRequired", false, "rotateSigningKey", false, "responseEncrypt", true)).expectProblem(400, "invalid_public_key");
        admin.put(ws("/service-accounts/" + saId + "/clients/" + clientRowId + "/security"),
                Map.of("hmacRequired", false, "rotateSigningKey", false, "responseEncrypt", true, "publicKey", weak))
                .expectProblem(400, "invalid_public_key");
    }
}
