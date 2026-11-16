package io.opendbapi.admin.workspace;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.policy.ServingGuard;
import io.opendbapi.core.policy.ServingPolicy;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Firewall and caller rate limits of a workspace (spec §14). Members read; admins change. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/serving-policy", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "serving-policy")
public class ServingPolicyController {

    private final WorkspaceAccess access;
    private final ServingGuard guard;
    private final AuditLog audit;

    public ServingPolicyController(WorkspaceAccess access, ServingGuard guard, AuditLog audit) {
        this.access = access;
        this.guard = guard;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "getServingPolicy", summary = "Firewall and rate limits")
    public ServingPolicy get(@PathVariable String ws) {
        return guard.policy(access.require(ws, WorkspaceRole.VIEWER).workspace().id());
    }

    @PutMapping
    @Operation(operationId = "saveServingPolicy", summary = "Replace firewall and rate limits (admin)")
    public ServingPolicy save(@PathVariable String ws, @RequestBody ServingPolicy policy) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        guard.save(wsId, policy);
        audit.record(wsId, granted.user().id(), "serving_policy.update", "workspace", wsId.toString(),
                Map.of("firewall", policy.firewall().mode().name(), "entries", policy.firewall().entries().size()));
        return guard.policy(wsId);
    }
}
