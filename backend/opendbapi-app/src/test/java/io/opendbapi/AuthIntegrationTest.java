package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Local sign-in, sessions, CSRF and lockout (spec §8.4, §8.5). */
class AuthIntegrationTest extends AppTestSupport {

    private String createLocalUser(ApiClient admin, String username, boolean systemAdmin) {
        return admin.post("/admin/api/users", Map.of(
                        "username", username, "password", username + "-password!", "systemAdmin", systemAdmin))
                .expect(201).json().path("id").asText();
    }

    @Test
    void bootstrapAdminSignsInAndSeesThemselves() {
        // Arrange
        ApiClient admin = client();

        // Act
        admin.login("admin", ADMIN_PASSWORD).expect(204);
        var me = admin.get("/admin/api/me").expect(200).json();

        // Assert
        assertThat(me.path("username").asText()).isEqualTo("admin");
        assertThat(me.path("systemAdmin").asBoolean()).isTrue();
        assertThat(me.path("source").asText()).isEqualTo("local");
    }

    @Test
    void signedOutCallsGetAProblem401() {
        // Act + Assert
        client().get("/admin/api/me").expectProblem(401, "unauthenticated");
    }

    @Test
    void mutatingWithoutCsrfTokenIsRefused() {
        // Act + Assert
        client().withoutCsrf().login("admin", ADMIN_PASSWORD).expectProblem(403, "csrf");
    }

    @Test
    void signInRotatesTheSessionAndSignOutEndsIt() {
        // Arrange
        ApiClient admin = client();
        admin.get("/admin/api/auth/providers").expect(200);
        admin.post("/admin/api/auth/logout", null).expect(204); // creates nothing to keep
        String before = admin.cookie("OPENDBAPI_SESSION");

        // Act
        admin.login("admin", ADMIN_PASSWORD).expect(204);
        String after = admin.cookie("OPENDBAPI_SESSION");
        admin.post("/admin/api/auth/logout", null).expect(204);

        // Assert
        assertThat(after).isNotNull().isNotEqualTo(before);
        admin.get("/admin/api/me").expectProblem(401, "unauthenticated");
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String name = "same-" + UUID.randomUUID().toString().substring(0, 8);
        createLocalUser(admin, name, false);

        // Act
        var wrongPassword = client().login(name, "not-the-password");
        var unknownUser = client().login("nobody-" + name, "not-the-password");

        // Assert
        wrongPassword.expectProblem(401, "invalid_credentials");
        unknownUser.expectProblem(401, "invalid_credentials");
        assertThat(wrongPassword.json().path("detail")).isEqualTo(unknownUser.json().path("detail"));
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheRightPassword() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String name = "lock-" + UUID.randomUUID().toString().substring(0, 8);
        createLocalUser(admin, name, false);
        ApiClient attacker = client();

        // Act
        for (int i = 0; i < 4; i++) {
            attacker.login(name, "guess-" + i).expectProblem(401, "invalid_credentials");
        }
        var fifth = attacker.login(name, "guess-5");
        var rightPasswordWhileLocked = client().login(name, name + "-password!");

        // Assert
        fifth.expectProblem(429, "account_locked");
        rightPasswordWhileLocked.expectProblem(429, "account_locked");
        assertThat(rightPasswordWhileLocked.json().path("detail").asText()).contains("Try again in 15 minutes");
    }

    @Test
    void disablingAUserEndsTheirExistingSession() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String name = "gone-" + UUID.randomUUID().toString().substring(0, 8);
        String id = createLocalUser(admin, name, false);
        ApiClient user = client();
        user.login(name, name + "-password!").expect(204);
        user.get("/admin/api/me").expect(200);

        // Act
        admin.patch("/admin/api/users/" + id, Map.of("disabled", true)).expect(200);

        // Assert
        user.get("/admin/api/me").expectProblem(401, "unauthenticated");
    }

    @Test
    void nonAdminsCannotAdministerUsers() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String name = "plain-" + UUID.randomUUID().toString().substring(0, 8);
        createLocalUser(admin, name, false);
        ApiClient user = client();
        user.login(name, name + "-password!").expect(204);

        // Act + Assert
        user.get("/admin/api/users").expectProblem(403, "forbidden");
        user.post("/admin/api/workspaces", Map.of("slug", "nope-" + name, "name", "Nope")).expectProblem(403, "forbidden");
    }

    @Test
    void adminCannotLockThemselvesOut() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String selfId = admin.get("/admin/api/me").json().path("id").asText();

        // Act + Assert
        admin.patch("/admin/api/users/" + selfId, Map.of("systemAdmin", false)).expectProblem(409, "self_lockout");
    }

    @Test
    void weakPasswordsAndUnknownFieldsAreRefused() {
        // Arrange
        ApiClient admin = signedInAdmin();

        // Act + Assert
        admin.post("/admin/api/users", Map.of("username", "weak-user", "password", "short", "systemAdmin", false))
                .expectProblem(400, "weak_password");
        admin.post("/admin/api/users", Map.of("username", "typo-user", "password", "long-enough-password",
                        "systemAdmn", true))
                .expectProblem(400, "invalid_body");
    }

    @Test
    void userChangesOwnPasswordWithTheCurrentOne() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String name = "pw-" + UUID.randomUUID().toString().substring(0, 8);
        createLocalUser(admin, name, false);
        ApiClient user = client();
        user.login(name, name + "-password!").expect(204);

        // Act
        user.post("/admin/api/me/password", Map.of("currentPassword", "wrong", "newPassword", "a-brand-new-password"))
                .expectProblem(400, "wrong_password");
        user.post("/admin/api/me/password", Map.of("currentPassword", name + "-password!", "newPassword",
                "a-brand-new-password")).expect(204);

        // Assert
        client().login(name, "a-brand-new-password").expect(204);
    }

    @Test
    void signInsAndFailuresAreAudited() {
        // Arrange
        ApiClient admin = signedInAdmin();
        client().login("admin", "definitely-wrong");

        // Act
        var entries = admin.get("/admin/api/audit?limit=20").expect(200).json();

        // Assert
        assertThat(entries.findValuesAsText("action")).contains("auth.login", "auth.login_failed");
        assertThat(entries.toString()).doesNotContain("definitely-wrong");
    }
}
