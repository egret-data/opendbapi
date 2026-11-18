package io.opendbapi.admin.endpoint;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.client.CallerAuthorizer;
import io.opendbapi.core.endpoint.ApiGroupStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint groups (display folders, spec §21.2). */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/groups", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "groups")
public class GroupController {

    public record SaveGroup(@NotBlank @Size(max = 120) String name) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final ApiGroupStore groups;
    private final AuditLog audit;
    private final CallerAuthorizer callers;

    public GroupController(WorkspaceAccess access, WorkspaceScope scope, ApiGroupStore groups, AuditLog audit, CallerAuthorizer callers) {
        this.access = access;
        this.scope = scope;
        this.groups = groups;
        this.audit = audit;
        this.callers = callers;
    }

    @GetMapping
    @Operation(operationId = "listGroups", summary = "Endpoint groups with their endpoint counts")
    public List<ApiGroupStore.ApiGroup> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), groups::list);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createGroup", summary = "Create a group (author)")
    public ApiGroupStore.ApiGroup create(@PathVariable String ws, @Valid @RequestBody SaveGroup body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        UUID wsId = granted.workspace().id();
        ApiGroupStore.ApiGroup created = scope.call(wsId, () -> groups.create(wsId, body.name().strip()));
        audit.record(wsId, granted.user().id(), "group.create", "group", created.id().toString(), Map.of("name", created.name()));
        return created;
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "renameGroup", summary = "Rename a group (author)")
    public ResponseEntity<Void> rename(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveGroup body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        if (!scope.call(granted.workspace().id(), () -> groups.rename(id, body.name().strip()))) {
            throw ApiProblem.notFound("group_not_found", "That group does not exist.");
        }
        audit.record(granted.workspace().id(), granted.user().id(), "group.rename", "group", id.toString(), Map.of("name", body.name().strip()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteGroup", summary = "Delete a group; its endpoints become ungrouped and grants on it are revoked (author)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        if (!scope.call(granted.workspace().id(), () -> groups.delete(id))) {
            throw ApiProblem.notFound("group_not_found", "That group does not exist.");
        }
        callers.invalidate(granted.workspace().id());
        audit.record(granted.workspace().id(), granted.user().id(), "group.delete", "group", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }
}
