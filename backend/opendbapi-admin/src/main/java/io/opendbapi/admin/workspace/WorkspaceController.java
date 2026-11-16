package io.opendbapi.admin.workspace;

import io.opendbapi.admin.security.CurrentUser;
import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Workspaces (spec §4.0, §21.1). Listing, creating and archiving are for system admins. */
@RestController
@RequestMapping(path = "/admin/api/workspaces", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "workspaces")
public class WorkspaceController {

    public record WorkspaceView(
            UUID id,
            String slug,
            String name,
            String status,
            Map<String, Object> quotas,
            @Schema(nullable = true) OffsetDateTime archivedAt,
            OffsetDateTime createdAt) {

        static WorkspaceView of(Workspace w) {
            return new WorkspaceView(w.id(), w.slug(), w.name(), w.status(), w.quotas(), w.archivedAt(), w.createdAt());
        }
    }

    public record CreateWorkspace(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
                    message = "must be 2-63 lowercase letters, digits or hyphens, starting with a letter or digit")
            String slug,
            @NotBlank @Size(max = 120) String name,
            @Schema(nullable = true, description = "First owner; defaults to the caller") UUID ownerUserId) {
    }

    public record UpdateWorkspace(
            @Schema(nullable = true) @Size(min = 1, max = 120) String name,
            @Schema(nullable = true) Map<String, Object> quotas) {
    }

    private final CurrentUser currentUser;
    private final WorkspaceAccess access;
    private final WorkspaceStore workspaces;
    private final MembershipStore memberships;
    private final UserStore users;
    private final AuditLog audit;

    public WorkspaceController(CurrentUser currentUser, WorkspaceAccess access, WorkspaceStore workspaces,
                               MembershipStore memberships, UserStore users, AuditLog audit) {
        this.currentUser = currentUser;
        this.access = access;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "list", summary = "Every workspace, archived included (system admin)")
    public List<WorkspaceView> list() {
        currentUser.requireSystemAdmin();
        return workspaces.list().stream().map(WorkspaceView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(operationId = "create", summary = "Create a workspace with its first owner (system admin)")
    public WorkspaceView create(@Valid @RequestBody CreateWorkspace body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        UUID ownerId = body.ownerUserId() != null ? body.ownerUserId() : caller.id();
        UserAccount owner = users.findById(ownerId)
                .orElseThrow(() -> ApiProblem.badRequest("unknown_user", "The chosen owner does not exist."));
        Workspace created = workspaces.create(body.slug(), body.name());
        memberships.upsert(created.id(), owner.id(), WorkspaceRole.OWNER, false);
        audit.record(created.id(), caller.id(), "workspace.create", "workspace", created.slug(),
                Map.of("name", created.name(), "owner", owner.username()));
        return WorkspaceView.of(created);
    }

    @GetMapping("/{ws}")
    @Operation(operationId = "get", summary = "One workspace (any member)")
    public WorkspaceView get(@PathVariable String ws) {
        return WorkspaceView.of(access.require(ws, WorkspaceRole.VIEWER).workspace());
    }

    @PatchMapping("/{ws}")
    @Operation(operationId = "update", summary = "Rename or change quotas (workspace owner)")
    public WorkspaceView update(@PathVariable String ws, @Valid @RequestBody UpdateWorkspace body) {
        var granted = access.require(ws, WorkspaceRole.OWNER);
        if (body.quotas() != null && !granted.user().systemAdmin()) {
            throw ApiProblem.forbidden("Only a system administrator can change quotas.");
        }
        Workspace updated = workspaces.update(granted.workspace().id(), body.name(), body.quotas());
        audit.record(updated.id(), granted.user().id(), "workspace.update", "workspace", updated.slug(),
                Map.of("name", updated.name()));
        return WorkspaceView.of(updated);
    }

    @PostMapping("/{ws}:archive")
    @Operation(operationId = "archive", summary = "Stop serving and hide a workspace (system admin)")
    public WorkspaceView archive(@PathVariable String ws) {
        return setArchived(ws, true);
    }

    @PostMapping("/{ws}:restore")
    @Operation(operationId = "restore", summary = "Bring an archived workspace back (system admin)")
    public WorkspaceView restore(@PathVariable String ws) {
        return setArchived(ws, false);
    }

    private WorkspaceView setArchived(String slug, boolean archived) {
        UserAccount caller = currentUser.requireSystemAdmin();
        Workspace workspace = workspaces.findBySlug(slug)
                .orElseThrow(() -> ApiProblem.notFound("workspace_not_found", "Workspace '%s' does not exist.".formatted(slug)));
        Workspace updated = workspaces.setArchived(workspace.id(), archived);
        audit.record(updated.id(), caller.id(), archived ? "workspace.archive" : "workspace.restore", "workspace",
                updated.slug(), Map.of());
        return WorkspaceView.of(updated);
    }
}
