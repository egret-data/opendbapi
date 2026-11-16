package io.opendbapi.admin.workspace;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserStore;
import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace members (spec §8.4, §21.2). Admins manage members; only owners grant or take
 * away the owner role; the last owner cannot leave. Roles that come from a directory group
 * mapping are read-only here; change the group or the mapping instead.
 */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "members")
public class MemberController {

    public enum Role { owner, admin, script_author, author, viewer }

    public record MemberView(
            UUID userId,
            String username,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true) String email,
            String source,
            Role role,
            boolean fromMapping,
            OffsetDateTime createdAt) {

        static MemberView of(MembershipStore.Member m) {
            return new MemberView(m.userId(), m.username(), m.displayName(), m.email(), m.source(),
                    Role.valueOf(m.role().dbValue()), m.fromMapping(), m.createdAt());
        }
    }

    public record AddMember(@NotNull UUID userId, @NotNull Role role) {
    }

    public record ChangeRole(@NotNull Role role) {
    }

    public record UserMatch(UUID id, String username, @Schema(nullable = true) String displayName,
                            @Schema(nullable = true) String email, String source) {
    }

    private final WorkspaceAccess access;
    private final MembershipStore memberships;
    private final UserStore users;
    private final AuditLog audit;

    public MemberController(WorkspaceAccess access, MembershipStore memberships, UserStore users, AuditLog audit) {
        this.access = access;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/members")
    @Operation(operationId = "list", summary = "Members and their roles (any member)")
    public List<MemberView> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return memberships.members(granted.workspace().id()).stream().map(MemberView::of).toList();
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(operationId = "add", summary = "Add a user to the workspace (workspace admin)")
    public MemberView add(@PathVariable String ws, @Valid @RequestBody AddMember body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        WorkspaceRole role = WorkspaceRole.fromDb(body.role().name());
        requireMayGrant(granted, role);
        var user = users.findById(body.userId())
                .filter(u -> !u.disabled())
                .orElseThrow(() -> ApiProblem.badRequest("unknown_user", "That user does not exist or is disabled."));
        if (memberships.roleOf(granted.workspace().id(), user.id()).isPresent()) {
            throw ApiProblem.conflict("already_member", "%s is already a member.".formatted(user.username()));
        }
        memberships.upsert(granted.workspace().id(), user.id(), role, false);
        audit.record(granted.workspace().id(), granted.user().id(), "member.add", "user", user.id().toString(),
                Map.of("username", user.username(), "role", role.dbValue()));
        return find(granted.workspace().id(), user.id());
    }

    @PatchMapping("/members/{userId}")
    @Transactional
    @Operation(operationId = "changeRole", summary = "Change a member's role (workspace admin)")
    public MemberView changeRole(@PathVariable String ws, @PathVariable UUID userId, @Valid @RequestBody ChangeRole body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        MemberView current = find(granted.workspace().id(), userId);
        requireEditable(current);
        WorkspaceRole from = WorkspaceRole.fromDb(current.role().name());
        WorkspaceRole to = WorkspaceRole.fromDb(body.role().name());
        requireMayGrant(granted, from);
        requireMayGrant(granted, to);
        if (from == WorkspaceRole.OWNER && to != WorkspaceRole.OWNER) {
            requireAnotherOwner(granted.workspace().id());
        }
        memberships.upsert(granted.workspace().id(), userId, to, false);
        audit.record(granted.workspace().id(), granted.user().id(), "member.role", "user", userId.toString(),
                Map.of("username", current.username(), "from", from.dbValue(), "to", to.dbValue()));
        return find(granted.workspace().id(), userId);
    }

    @DeleteMapping("/members/{userId}")
    @Transactional
    @Operation(operationId = "remove", summary = "Remove a member (workspace admin)")
    public ResponseEntity<Void> remove(@PathVariable String ws, @PathVariable UUID userId) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        MemberView current = find(granted.workspace().id(), userId);
        requireEditable(current);
        WorkspaceRole role = WorkspaceRole.fromDb(current.role().name());
        requireMayGrant(granted, role);
        if (role == WorkspaceRole.OWNER) {
            requireAnotherOwner(granted.workspace().id());
        }
        memberships.remove(granted.workspace().id(), userId);
        audit.record(granted.workspace().id(), granted.user().id(), "member.remove", "user", userId.toString(),
                Map.of("username", current.username(), "role", role.dbValue()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user-search")
    @Operation(operationId = "searchUsers", summary = "Find users to add (workspace admin)")
    public List<UserMatch> searchUsers(@PathVariable String ws, @RequestParam @Size(min = 1, max = 100) String q) {
        access.require(ws, WorkspaceRole.ADMIN);
        return users.search(q, 20).stream()
                .map(u -> new UserMatch(u.id(), u.username(), u.displayName(), u.email(), u.source()))
                .toList();
    }

    private MemberView find(UUID workspaceId, UUID userId) {
        return memberships.members(workspaceId).stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .map(MemberView::of)
                .orElseThrow(() -> ApiProblem.notFound("member_not_found", "That user is not a member of this workspace."));
    }

    private static void requireEditable(MemberView member) {
        if (member.fromMapping()) {
            throw ApiProblem.conflict("managed_by_directory",
                    "%s's role comes from a directory group mapping. Change the group membership or the mapping instead."
                            .formatted(member.username()));
        }
    }

    private static void requireMayGrant(WorkspaceAccess.Granted granted, WorkspaceRole role) {
        if (role == WorkspaceRole.OWNER && granted.role() != WorkspaceRole.OWNER) {
            throw ApiProblem.forbidden("Only an owner can grant or change the owner role.");
        }
    }

    private void requireAnotherOwner(UUID workspaceId) {
        if (memberships.countOwners(workspaceId) <= 1) {
            throw ApiProblem.conflict("last_owner", "A workspace needs at least one owner. Make someone else owner first.");
        }
    }
}
