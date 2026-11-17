package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Personal API tokens for automation (spec §8.6). */
class ApiTokenIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String other;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "tk-" + UUID.randomUUID().toString().substring(0, 8);
        other = "tk-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Tokens " + ws)).expect(201);
        admin.post("/admin/api/workspaces", Map.of("slug", other, "name", "Other " + other)).expect(201);
    }

    private ApiClient bearer(String secret) {
        return client().withoutCsrf().header("Authorization", "Bearer " + secret);
    }

    @Test
    void aWorkspaceTokenActsAsItsUserInThatWorkspaceOnly() {
        // Arrange
        Map<String, Object> body = new HashMap<>(Map.of("name", "CI deploy", "workspace", ws, "expiresInDays", 30));
        JsonNode created = admin.post("/admin/api/me/tokens", body).expect(201).json();
        String secret = created.path("secret").asText();
        ApiClient ci = bearer(secret);

        // Act
        var listed = admin.get("/admin/api/me/tokens").expect(200).body();
        var read = ci.get("/admin/api/ws/" + ws + "/datasources");
        var write = ci.post("/admin/api/ws/" + ws + "/groups", Map.of("name", "from-ci"));
        var elsewhere = ci.get("/admin/api/ws/" + other + "/datasources");
        var systemWide = ci.get("/admin/api/users");
        var mintMore = ci.post("/admin/api/me/tokens", Map.of("name", "sneaky"));

        // Assert
        assertThat(secret).startsWith("odb_").hasSizeGreaterThan(40);
        assertThat(created.path("token").path("prefix").asText()).isEqualTo(secret.substring(0, 10));
        assertThat(created.path("token").path("workspace").asText()).isEqualTo(ws);
        assertThat(listed).contains("CI deploy").doesNotContain(secret);
        read.expect(200);
        write.expect(201);
        elsewhere.expectProblem(404, "workspace_not_found");
        systemWide.expect(403);
        mintMore.expect(403);
    }

    @Test
    void revokedOrUnknownTokensAreRefused() {
        // Arrange
        JsonNode created = admin.post("/admin/api/me/tokens", Map.of("name", "short-lived")).expect(201).json();
        String secret = created.path("secret").asText();
        bearer(secret).get("/admin/api/ws/" + other + "/datasources").expect(200);

        // Act
        admin.delete("/admin/api/me/tokens/" + created.path("token").path("id").asText()).expect(204);
        var revoked = bearer(secret).get("/admin/api/ws/" + other + "/datasources");
        var unknown = bearer("odb_not-a-real-token").get("/admin/api/ws/" + other + "/datasources");
        var badExpiry = admin.post("/admin/api/me/tokens", Map.of("name", "x", "expiresInDays", 999));

        // Assert
        revoked.expectProblem(401, "invalid_token");
        unknown.expectProblem(401, "invalid_token");
        badExpiry.expectProblem(400, "invalid_token");
        assertThat(admin.get("/admin/api/me/tokens").expect(200).body()).doesNotContain("short-lived");
    }
}
