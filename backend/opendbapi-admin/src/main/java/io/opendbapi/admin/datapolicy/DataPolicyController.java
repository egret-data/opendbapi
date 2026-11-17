package io.opendbapi.admin.datapolicy;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.datapolicy.DataPolicies;
import io.opendbapi.core.datapolicy.DataPolicy;
import io.opendbapi.core.datapolicy.DataPolicyStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Column masks and row filters (spec §12, §21.2). Members read; workspace admins change. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/data-policies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "data-policies")
public class DataPolicyController {

    /**
     * Create or change a policy. On change, kind, datasource and endpoint stay as they are.
     */
    public record SaveDataPolicy(
            @NotNull @Schema(allowableValues = {"mask", "row_filter"}) String kind,
            @Schema(nullable = true) UUID datasourceId,
            @Schema(nullable = true) UUID endpointId,
            @NotBlank String column,
            @Schema(nullable = true) DataPolicy.Mask mask,
            @Schema(nullable = true) String attribute,
            @Schema(nullable = true) List<UUID> exemptServiceAccounts) {

        DataPolicy toPolicy(String kind, UUID datasourceId, UUID endpointId) {
            return new DataPolicy(null, kind, datasourceId, endpointId, column.strip(),
                    DataPolicy.MASK.equals(kind) ? mask : null,
                    DataPolicy.ROW_FILTER.equals(kind) && attribute != null ? attribute.strip() : null,
                    exemptServiceAccounts == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(exemptServiceAccounts)),
                    null, null);
        }
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final DataPolicyStore store;
    private final DataPolicies policies;
    private final ServiceAccountStore accounts;
    private final AuditLog audit;

    public DataPolicyController(WorkspaceAccess access, WorkspaceScope scope, DataPolicyStore store, DataPolicies policies,
                                ServiceAccountStore accounts, AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.store = store;
        this.policies = policies;
        this.accounts = accounts;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listDataPolicies", summary = "Data policies, optionally of one datasource or endpoint")
    public List<DataPolicy> list(@PathVariable String ws, @RequestParam(required = false) UUID datasourceId,
                                 @RequestParam(required = false) UUID endpointId) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> store.list(datasourceId, endpointId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDataPolicy", summary = "Add a mask or row filter (admin)")
    public DataPolicy create(@PathVariable String ws, @Valid @RequestBody SaveDataPolicy body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        DataPolicy policy = body.toPolicy(body.kind(), body.datasourceId(), body.endpointId());
        DataPolicies.check(policy);
        DataPolicy created = scope.call(wsId, () -> {
            requireAccounts(policy.exemptServiceAccounts());
            UUID id = store.create(wsId, policy, granted.user().id());
            audit.record(wsId, granted.user().id(), "data_policy.create", "data_policy", id.toString(), details(policy));
            return store.find(id).orElseThrow();
        });
        policies.invalidate(wsId);
        return created;
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateDataPolicy", summary = "Change a policy's column, mask, attribute or exemptions (admin)")
    public DataPolicy update(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveDataPolicy body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        DataPolicy updated = scope.call(wsId, () -> {
            DataPolicy current = store.find(id).orElseThrow(DataPolicyController::notFound);
            DataPolicy policy = body.toPolicy(current.kind(), current.datasourceId(), current.endpointId());
            DataPolicies.check(policy);
            requireAccounts(policy.exemptServiceAccounts());
            store.update(id, policy);
            audit.record(wsId, granted.user().id(), "data_policy.update", "data_policy", id.toString(), details(policy));
            return store.find(id).orElseThrow();
        });
        policies.invalidate(wsId);
        return updated;
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteDataPolicy", summary = "Remove a policy (admin)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            DataPolicy current = store.find(id).orElseThrow(DataPolicyController::notFound);
            store.delete(id);
            audit.record(wsId, granted.user().id(), "data_policy.delete", "data_policy", id.toString(), details(current));
        });
        policies.invalidate(wsId);
        return ResponseEntity.noContent().build();
    }

    private void requireAccounts(List<UUID> ids) {
        for (UUID id : ids) {
            if (accounts.find(id).isEmpty()) {
                throw ApiProblem.badRequest("invalid_policy", "Exempt service account %s does not exist.".formatted(id));
            }
        }
    }

    private static Map<String, Object> details(DataPolicy p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", p.kind());
        out.put("column", p.column());
        if (p.datasourceId() != null) {
            out.put("datasourceId", p.datasourceId().toString());
        }
        if (p.endpointId() != null) {
            out.put("endpointId", p.endpointId().toString());
        }
        if (p.mask() != null) {
            out.put("mask", p.mask().type());
        }
        if (p.attribute() != null) {
            out.put("attribute", p.attribute());
        }
        out.put("exempt", p.exemptServiceAccounts().size());
        return out;
    }

    private static ApiProblem notFound() {
        return ApiProblem.notFound("data_policy_not_found", "That data policy does not exist.");
    }
}
