package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** LDAP sign-in against a real OpenLDAP, provisioning and group → role sync (spec §8.5). */
class LdapSignInIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String idpId;
    private String slug;

    private Map<String, Object> ldapConfig(boolean allowInsecure) {
        return Map.of(
                "url", ldapUrl(),
                "bindDn", "cn=admin,dc=opendbapi,dc=local",
                "userSearchBase", "ou=users,dc=opendbapi,dc=local",
                "userSearchFilter", "(uid={0})",
                "groupSearchBase", "ou=groups,dc=opendbapi,dc=local",
                "groupSearchFilter", "(member={0})",
                "allowInsecure", allowInsecure);
    }

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        slug = "sales-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", slug, "name", "Sales")).expect(201);
        idpId = admin.post("/admin/api/identity-providers", Map.of(
                        "type", "ldap", "name", "Corp LDAP " + slug, "enabled", true,
                        "config", ldapConfig(true), "secret", "env:TEST_LDAP_BIND_PASSWORD"))
                .expect(201).json().path("id").asText();
        admin.put("/admin/api/identity-providers/" + idpId + "/group-mappings", List.of(
                Map.of("externalGroup", "sales-authors", "workspace", slug, "role", "author", "managed", false),
                Map.of("externalGroup", "opendbapi-admins", "role", "system_admin", "managed", false)))
                .expect(200);
    }

    @Test
    void directoryUserIsProvisionedWithTheMappedRole() {
        // Arrange
        ApiClient bob = client();

        // Act
        bob.ldapLogin(idpId, "bob", "bob").expect(204);
        var me = bob.get("/admin/api/me").expect(200).json();

        // Assert
        assertThat(me.path("source").asText()).isEqualTo("ldap");
        assertThat(me.path("displayName").asText()).isEqualTo("Bob Tran");
        assertThat(me.path("email").asText()).isEqualTo("bob@opendbapi.local");
        assertThat(me.path("systemAdmin").asBoolean()).isFalse();
        assertThat(me.path("workspaces").toString()).contains(slug).contains("\"role\":\"author\"");
    }

    @Test
    void adminGroupGrantsSystemAdmin() {
        // Arrange
        ApiClient alice = client();

        // Act
        alice.ldapLogin(idpId, "alice", "alice").expect(204);

        // Assert
        assertThat(alice.get("/admin/api/me").json().path("systemAdmin").asBoolean()).isTrue();
        alice.get("/admin/api/users").expect(200);
    }

    @Test
    void wrongDirectoryPasswordIsTheSameGenericError() {
        // Act + Assert
        client().ldapLogin(idpId, "bob", "nope").expectProblem(401, "invalid_credentials");
        client().ldapLogin(idpId, "nobody", "nope").expectProblem(401, "invalid_credentials");
        client().ldapLogin(idpId, "bob", "").expectProblem(400, "invalid_request");
    }

    @Test
    void mappedRolesCannotBeEditedByHand() {
        // Arrange
        ApiClient bob = client();
        bob.ldapLogin(idpId, "bob", "bob").expect(204);
        String bobId = bob.get("/admin/api/me").json().path("id").asText();

        // Act + Assert
        admin.patch("/admin/api/ws/" + slug + "/members/" + bobId, Map.of("role", "viewer"))
                .expectProblem(409, "managed_by_directory");
    }

    @Test
    void providerSecretIsNeverReturned() {
        // Act
        var provider = admin.get("/admin/api/identity-providers/" + idpId).expect(200);

        // Assert
        assertThat(provider.json().path("hasSecret").asBoolean()).isTrue();
        assertThat(provider.json().path("secretEnv").asText()).isEqualTo("env:TEST_LDAP_BIND_PASSWORD");
        assertThat(provider.body()).doesNotContain("\"admin\"");

        // Act: a plain secret is sealed and not even its reference is shown
        admin.patch("/admin/api/identity-providers/" + idpId, Map.of("type", "ldap", "name", "Corp LDAP " + slug,
                "enabled", true, "config", ldapConfig(true), "secret", "admin")).expect(200);
        var sealed = admin.get("/admin/api/identity-providers/" + idpId).json();

        // Assert: still works with the sealed secret
        assertThat(sealed.path("secretEnv").isNull()).isTrue();
        client().ldapLogin(idpId, "bob", "bob").expect(204);
    }

    @Test
    void testActionReportsUserAndGroups() {
        // Act
        var result = admin.post("/admin/api/identity-providers/" + idpId + ":test",
                Map.of("username", "bob", "password", "bob")).expect(200).json();

        // Assert
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("messages").toString()).contains("uid=bob,ou=users,dc=opendbapi,dc=local", "sales-authors");
    }

    @Test
    void plainLdapIsRefusedUnlessExplicitlyAllowed() {
        // Arrange
        admin.patch("/admin/api/identity-providers/" + idpId, Map.of("type", "ldap", "name", "Corp LDAP " + slug,
                "enabled", true, "config", ldapConfig(false))).expect(200);

        // Act + Assert
        client().ldapLogin(idpId, "bob", "bob").expectProblem(400, "insecure_ldap");
    }

    @Test
    void providersEndpointListsEnabledDirectories() {
        // Act
        var providers = client().get("/admin/api/auth/providers").expect(200).json();

        // Assert
        assertThat(providers.path("local").asBoolean()).isTrue();
        assertThat(providers.path("ldap").findValuesAsText("id")).contains(idpId);
    }
}
