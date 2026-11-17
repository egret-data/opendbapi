package io.opendbapi.admin.security;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceStore;
import org.springframework.stereotype.Component;

/**
 * Role checks for {@code /admin/api/ws/{ws}/**} (spec §8.4), done in code on every call
 * rather than only hidden in the UI.
 *
 * <p>A workspace the caller does not belong to answers 404, exactly like one that does not
 * exist, so membership cannot be probed. System admins act as owners of every active
 * workspace, which is how a workspace gets its first members.
 */
@Component
public class WorkspaceAccess {

    public record Granted(Workspace workspace, UserAccount user, WorkspaceRole role) {
    }

    private final CurrentUser currentUser;
    private final WorkspaceStore workspaces;
    private final MembershipStore memberships;

    public WorkspaceAccess(CurrentUser currentUser, WorkspaceStore workspaces, MembershipStore memberships) {
        this.currentUser = currentUser;
        this.workspaces = workspaces;
        this.memberships = memberships;
    }

    public Granted require(String slug, WorkspaceRole minimum) {
        UserAccount user = currentUser.require();
        Workspace workspace = workspaces.findBySlug(slug)
                .filter(Workspace::active)
                .orElseThrow(() -> notFound(slug));
        TokenScope token = TokenScope.current();
        if (token != null && token.workspaceId() != null && !token.workspaceId().equals(workspace.id())) {
            throw notFound(slug); // the token is for another workspace
        }
        WorkspaceRole role = user.systemAdmin()
                ? WorkspaceRole.OWNER
                : memberships.roleOf(workspace.id(), user.id()).orElseThrow(() -> notFound(slug));
        if (!role.atLeast(minimum)) {
            throw ApiProblem.forbidden("This needs the %s role in workspace '%s'; you are %s."
                    .formatted(minimum.dbValue(), slug, role.dbValue()));
        }
        return new Granted(workspace, user, role);
    }

    private static ApiProblem notFound(String slug) {
        return ApiProblem.notFound("workspace_not_found", "Workspace '%s' does not exist or you are not a member.".formatted(slug));
    }
}
