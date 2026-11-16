package io.opendbapi.core.identity;

import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.WorkspaceRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes an LDAP/OIDC user's roles from their directory groups at every sign-in
 * (spec §8.5):
 * <ul>
 *   <li>a workspace the groups map to gets the highest mapped role, marked {@code from_mapping};
 *   <li>a mapped membership whose group the user left is removed;
 *   <li>a role granted by hand is kept, unless a {@code managed} mapping exists for that
 *       workspace, in which case the mappings alone decide;
 *   <li>the system-admin flag follows the mappings only when the provider has a
 *       {@code system_admin} mapping at all; otherwise it is left as an admin set it.
 * </ul>
 * Group names compare case-insensitively, as directories usually do.
 */
@Service
public class RoleSync {

    /** What changed, for the audit log. */
    public record Result(List<String> changes) {
    }

    private final IdentityProviderStore providers;
    private final MembershipStore memberships;
    private final UserStore users;

    public RoleSync(IdentityProviderStore providers, MembershipStore memberships, UserStore users) {
        this.providers = providers;
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional
    public Result sync(UserAccount user, Collection<String> groups) {
        Set<String> held = groups.stream().map(g -> g.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<GroupRoleMapping> all = providers.mappings(user.idpId());
        List<GroupRoleMapping> matching = all.stream()
                .filter(m -> held.contains(m.externalGroup().toLowerCase(Locale.ROOT)))
                .toList();
        List<String> changes = new ArrayList<>();

        Map<UUID, WorkspaceRole> desired = new HashMap<>();
        for (GroupRoleMapping m : matching) {
            if (!m.grantsSystemAdmin()) {
                desired.merge(m.workspaceId(), WorkspaceRole.fromDb(m.role()),
                        (a, b) -> a.atLeast(b) ? a : b);
            }
        }
        Set<UUID> managedWorkspaces = new HashSet<>();
        for (GroupRoleMapping m : all) {
            if (m.managed() && m.workspaceId() != null) {
                managedWorkspaces.add(m.workspaceId());
            }
        }

        Map<UUID, MembershipStore.Row> current = new HashMap<>();
        for (MembershipStore.Row row : memberships.rowsOf(user.id())) {
            current.put(row.workspaceId(), row);
        }

        for (MembershipStore.Row row : current.values()) {
            boolean decidedByMappings = row.fromMapping() || managedWorkspaces.contains(row.workspaceId());
            if (decidedByMappings && !desired.containsKey(row.workspaceId())) {
                memberships.remove(row.workspaceId(), user.id());
                changes.add("removed from workspace %s (was %s)".formatted(row.workspaceId(), row.role().dbValue()));
            }
        }
        desired.forEach((workspaceId, role) -> {
            MembershipStore.Row row = current.get(workspaceId);
            boolean manualAndUnmanaged = row != null && !row.fromMapping() && !managedWorkspaces.contains(workspaceId);
            if (manualAndUnmanaged) {
                return;
            }
            if (row == null || row.role() != role || !row.fromMapping()) {
                memberships.upsert(workspaceId, user.id(), role, true);
                changes.add("workspace %s: %s".formatted(workspaceId, role.dbValue()));
            }
        });

        boolean providerMapsSystemAdmin = all.stream().anyMatch(GroupRoleMapping::grantsSystemAdmin);
        if (providerMapsSystemAdmin) {
            boolean shouldBeAdmin = matching.stream().anyMatch(GroupRoleMapping::grantsSystemAdmin);
            if (shouldBeAdmin != user.systemAdmin()) {
                users.setSystemAdmin(user.id(), shouldBeAdmin);
                changes.add(shouldBeAdmin ? "granted system admin" : "revoked system admin");
            }
        }
        return new Result(changes);
    }
}
