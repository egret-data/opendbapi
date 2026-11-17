package io.opendbapi.admin.secret;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.WorkspaceSecrets;
import io.opendbapi.core.secret.WorkspaceSecrets.ManagedSecret;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** Secrets managed by OpenDBAPI (spec §4.5). Workspace admins only; values are write-only. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/secrets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "secrets")
public class SecretController {

    public record CreateSecret(@NotBlank @Size(max = 63) String name, @Schema(nullable = true) @Size(max = 500) String description,
                               @Schema(description = "Write-only; never returned") @NotBlank String value) {
    }

    /** @param value a new value, or null to keep the current one */
    public record UpdateSecret(@Schema(nullable = true) @Size(max = 500) String description,
                               @Schema(nullable = true, description = "Write-only; never returned") String value) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final WorkspaceSecrets secrets;
    private final DataSourceRegistry registry;
    private final AuditLog audit;

    public SecretController(WorkspaceAccess access, WorkspaceScope scope, WorkspaceSecrets secrets, DataSourceRegistry registry,
                            AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.secrets = secrets;
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listSecrets", summary = "Managed secrets and where they are used, without values (admin)")
    public List<ManagedSecret> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        return scope.call(granted.workspace().id(), secrets::list);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSecret", summary = "Store a secret; refer to it as secret:NAME (admin)")
    public ManagedSecret create(@PathVariable String ws, @Valid @RequestBody CreateSecret body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        String name = body.name().strip();
        return scope.call(wsId, () -> {
            secrets.create(wsId, name, blank(body.description()), body.value(), granted.user().id());
            audit.record(wsId, granted.user().id(), "secret.create", "secret", name, Map.of("name", name));
            return secrets.find(name).orElseThrow();
        });
    }

    @PatchMapping("/{name}")
    @Operation(operationId = "updateSecret", summary = "Describe a secret or replace its value (admin)")
    public ManagedSecret update(@PathVariable String ws, @PathVariable String name, @Valid @RequestBody UpdateSecret body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        boolean newValue = body.value() != null && !body.value().isEmpty();
        List<UUID> pooled = scope.call(wsId, () -> {
            if (!secrets.update(name, blank(body.description()), newValue ? body.value() : null)) {
                throw notFound(name);
            }
            audit.record(wsId, granted.user().id(), "secret.update", "secret", name, Map.of("name", name, "valueChanged", newValue));
            return newValue ? secrets.datasourcesUsing(name) : List.of();
        });
        // Connections were opened with the old password: the next query reconnects with the new one.
        pooled.forEach(registry::evict);
        return scope.call(wsId, () -> secrets.find(name).orElseThrow());
    }

    @DeleteMapping("/{name}")
    @Operation(operationId = "deleteSecret", summary = "Delete a secret no datasource or alert channel uses (admin)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable String name) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            if (!secrets.delete(name)) {
                throw notFound(name);
            }
            audit.record(wsId, granted.user().id(), "secret.delete", "secret", name, Map.of("name", name));
        });
        return ResponseEntity.noContent().build();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static ApiProblem notFound(String name) {
        return ApiProblem.notFound("secret_not_found", "There is no secret named '%s'.".formatted(name));
    }
}
