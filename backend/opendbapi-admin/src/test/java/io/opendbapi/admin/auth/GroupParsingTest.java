package io.opendbapi.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroupParsingTest {

    @Test
    void oidcGroupsClaimAcceptsListsStringsAndKeycloakPaths() {
        // Act + Assert
        assertThat(OidcSignIn.groups(List.of("/opendbapi-admins", "sales-authors")))
                .containsExactly("opendbapi-admins", "sales-authors");
        assertThat(OidcSignIn.groups("single")).containsExactly("single");
        assertThat(OidcSignIn.groups(null)).isEmpty();
        assertThat(OidcSignIn.groups(42)).isEmpty();
    }

    @Test
    void activeDirectoryMemberOfDnBecomesTheGroupName() {
        // Act + Assert
        assertThat(LdapLogin.firstRdnValue("CN=Sales Authors,OU=Groups,DC=corp,DC=local")).isEqualTo("Sales Authors");
        assertThat(LdapLogin.firstRdnValue("plain-group")).isEqualTo("plain-group");
    }
}
