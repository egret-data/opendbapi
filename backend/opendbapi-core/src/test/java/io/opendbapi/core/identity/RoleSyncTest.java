package io.opendbapi.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.PostgresTestSupport;
import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The group → role rules of spec §8.5, against the real schema. */
@SpringBootTest
class RoleSyncTest extends PostgresTestSupport {

    @Autowired RoleSync sync;
    @Autowired UserStore users;
    @Autowired WorkspaceStore workspaces;
    @Autowired MembershipStore memberships;
    @Autowired IdentityProviderStore providers;

    private IdentityProvider ldap;
    private Workspace sales;
    private Workspace finance;
    private UserAccount bob;

    @BeforeEach
    void arrange() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ldap = providers.create("ldap", "Corp " + suffix, true, new ObjectMapper().createObjectNode(), null);
        sales = workspaces.create("sales-" + suffix, "Sales");
        finance = workspaces.create("finance-" + suffix, "Finance");
        bob = users.upsertExternal("ldap", ldap.id(), "uid=bob-" + suffix, "bob-" + suffix, "Bob", null);
    }

    private void map(GroupRoleMapping... mappings) {
        providers.replaceMappings(ldap.id(), List.of(mappings));
    }

    private GroupRoleMapping m(String group, Workspace ws, String role, boolean managed) {
        return new GroupRoleMapping(null, ldap.id(), group, ws == null ? null : ws.id(), role, managed);
    }

    private Optional<WorkspaceRole> roleIn(Workspace ws) {
        return memberships.roleOf(ws.id(), bob.id());
    }

    private UserAccount reload() {
        return users.findById(bob.id()).orElseThrow();
    }

    @Test
    void grantsTheHighestMappedRoleAcrossGroups() {
        // Arrange
        map(m("sales-readers", sales, "viewer", false), m("sales-authors", sales, "author", false));

        // Act
        sync.sync(bob, List.of("Sales-Readers", "sales-authors"));

        // Assert
        assertThat(roleIn(sales)).contains(WorkspaceRole.AUTHOR);
        assertThat(roleIn(finance)).isEmpty();
    }

    @Test
    void leavingTheGroupRemovesTheMappedMembership() {
        // Arrange
        map(m("sales-authors", sales, "author", false));
        sync.sync(bob, List.of("sales-authors"));

        // Act
        var result = sync.sync(reload(), List.of());

        // Assert
        assertThat(roleIn(sales)).isEmpty();
        assertThat(result.changes()).anyMatch(c -> c.startsWith("removed from workspace"));
    }

    @Test
    void handGrantedRoleSurvivesAnUnmanagedMapping() {
        // Arrange: an admin made bob an admin of sales by hand
        memberships.upsert(sales.id(), bob.id(), WorkspaceRole.ADMIN, false);
        map(m("sales-readers", sales, "viewer", false));

        // Act: in the group, then out of it
        sync.sync(bob, List.of("sales-readers"));
        var afterJoin = roleIn(sales);
        sync.sync(reload(), List.of());

        // Assert
        assertThat(afterJoin).contains(WorkspaceRole.ADMIN);
        assertThat(roleIn(sales)).contains(WorkspaceRole.ADMIN);
    }

    @Test
    void managedMappingOverridesAndRevokesHandGrantedRoles() {
        // Arrange
        memberships.upsert(sales.id(), bob.id(), WorkspaceRole.ADMIN, false);
        memberships.upsert(finance.id(), bob.id(), WorkspaceRole.OWNER, false);
        map(m("sales-readers", sales, "viewer", true), m("finance-team", finance, "author", true));

        // Act: in sales-readers only
        sync.sync(bob, List.of("sales-readers"));

        // Assert
        assertThat(roleIn(sales)).contains(WorkspaceRole.VIEWER);
        assertThat(roleIn(finance)).as("managed and not in the group").isEmpty();
    }

    @Test
    void systemAdminFollowsMappingsOnlyWhenTheProviderMapsIt() {
        // Arrange: no system_admin mapping yet; an admin flags bob by hand
        map(m("sales-authors", sales, "author", false));
        users.setSystemAdmin(bob.id(), true);

        // Act + Assert: untouched while the provider does not map system admin
        sync.sync(reload(), List.of("sales-authors"));
        assertThat(reload().systemAdmin()).isTrue();

        // Act + Assert: once mapped, the group decides
        map(m("sales-authors", sales, "author", false), m("opendbapi-admins", null, "system_admin", false));
        var revoked = sync.sync(reload(), List.of("sales-authors"));
        assertThat(reload().systemAdmin()).isFalse();
        assertThat(revoked.changes()).contains("revoked system admin");

        sync.sync(reload(), List.of("OpenDBAPI-Admins"));
        assertThat(reload().systemAdmin()).isTrue();
    }
}
