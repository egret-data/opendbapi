package io.opendbapi.admin.client;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.client.CallerAuthorizer;
import io.opendbapi.core.client.ClientSecrets;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.client.ServiceAccountStore.Client;
import io.opendbapi.core.client.ServiceAccountStore.EndpointAccess;
import io.opendbapi.core.client.ServiceAccountStore.Grant;
import io.opendbapi.core.client.ServiceAccountStore.ServiceAccount;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service accounts, their OAuth2 clients and grants (spec §8.1, §4.4). Members read; workspace
 * admins change. A client secret is returned once, by the call that creates the client.
 */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "service-accounts")
public class ServiceAccountController {

    public record SaveServiceAccount(@NotBlank @Size(max = 120) String name,
                                     @Size(max = 1000) @Schema(nullable = true) String description,
                                     boolean disabled) {
    }

    public record ServiceAccountDetail(ServiceAccount summary, List<Client> clients, List<Grant> grants) {
    }

    /** @param expiresInDays the client stops working after this many days; null = never */
    public record CreateClient(@Min(1) @Max(3650) @Schema(nullable = true) Integer expiresInDays) {
    }

    public record NewClient(Client client, @Schema(description = "Shown once; store it now") String clientSecret) {
    }

    public record SaveGrant(@NotNull @Pattern(regexp = "endpoint|group") String resourceType, @NotNull UUID resourceId) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final ServiceAccountStore store;
    private final CallerAuthorizer authorizer;
    private final AuditLog audit;

    public ServiceAccountController(WorkspaceAccess access, WorkspaceScope scope, ServiceAccountStore store,
                                    CallerAuthorizer authorizer, AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.store = store;
        this.authorizer = authorizer;
        this.audit = audit;
    }

    @GetMapping("/service-accounts")
    @Operation(operationId = "listServiceAccounts", summary = "Service accounts of the workspace")
    public List<ServiceAccount> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), store::list);
    }

    @PostMapping("/service-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createServiceAccount", summary = "Create a service account (admin)")
    public ServiceAccountDetail create(@PathVariable String ws, @Valid @RequestBody SaveServiceAccount body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        return scope.call(wsId, () -> {
            UUID id = store.create(wsId, body.name().strip(), blankToNull(body.description()), granted.user().id());
            audit.record(wsId, granted.user().id(), "service_account.create", "service_account", id.toString(),
                    Map.of("name", body.name().strip()));
            return detail(id);
        });
    }

    @GetMapping("/service-accounts/{id}")
    @Operation(operationId = "getServiceAccount", summary = "A service account with its clients and grants")
    public ServiceAccountDetail get(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> detail(id));
    }

    @PatchMapping("/service-accounts/{id}")
    @Operation(operationId = "updateServiceAccount", summary = "Rename, describe, disable or enable (admin)")
    public ServiceAccountDetail update(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveServiceAccount body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        ServiceAccountDetail result = scope.call(wsId, () -> {
            if (!store.update(id, body.name().strip(), blankToNull(body.description()), body.disabled())) {
                throw notFound();
            }
            audit.record(wsId, granted.user().id(), "service_account.update", "service_account", id.toString(),
                    Map.of("name", body.name().strip(), "disabled", body.disabled()));
            return detail(id);
        });
        authorizer.invalidate(wsId);
        return result;
    }

    @DeleteMapping("/service-accounts/{id}")
    @Operation(operationId = "deleteServiceAccount", summary = "Delete a service account, its clients and grants (admin)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            ServiceAccount sa = store.find(id).orElseThrow(ServiceAccountController::notFound);
            store.delete(id);
            audit.record(wsId, granted.user().id(), "service_account.delete", "service_account", id.toString(),
                    Map.of("name", sa.name()));
        });
        authorizer.invalidate(wsId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/service-accounts/{id}/clients")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createClient", summary = "Create an OAuth2 client; the secret is returned only here (admin)")
    public NewClient createClient(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody CreateClient body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        String clientId = ClientSecrets.newClientId();
        String secret = ClientSecrets.newSecret();
        Instant expiresAt = body.expiresInDays() == null ? null : Instant.now().plus(Duration.ofDays(body.expiresInDays()));
        Client client = scope.call(wsId, () -> {
            store.find(id).orElseThrow(ServiceAccountController::notFound);
            Client created = store.createClient(wsId, id, clientId, ClientSecrets.hash(secret), expiresAt);
            Map<String, Object> detail = new HashMap<>();
            detail.put("clientId", clientId);
            detail.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            audit.record(wsId, granted.user().id(), "client.create", "service_account", id.toString(), detail);
            return created;
        });
        return new NewClient(client, secret);
    }

    @PostMapping("/service-accounts/{id}/clients/{clientRowId}:revoke")
    @Operation(operationId = "revokeClient", summary = "Revoke a client; its tokens stop working within seconds (admin)")
    public ResponseEntity<Void> revokeClient(@PathVariable String ws, @PathVariable UUID id, @PathVariable UUID clientRowId) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            String clientId = store.revokeClient(id, clientRowId)
                    .orElseThrow(() -> ApiProblem.notFound("client_not_found", "No active client with that id."));
            audit.record(wsId, granted.user().id(), "client.revoke", "service_account", id.toString(), Map.of("clientId", clientId));
        });
        authorizer.invalidate(wsId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/service-accounts/{id}/grants")
    @Operation(operationId = "grantAccess", summary = "Let the service account call an endpoint or every endpoint of a group (admin)")
    public ResponseEntity<Void> grant(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveGrant body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            store.find(id).orElseThrow(ServiceAccountController::notFound);
            store.grant(wsId, id, body.resourceType(), body.resourceId(), granted.user().id());
            audit.record(wsId, granted.user().id(), "grant.add", "service_account", id.toString(),
                    Map.of("resourceType", body.resourceType(), "resourceId", body.resourceId().toString()));
        });
        authorizer.invalidate(wsId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/service-accounts/{id}/grants/{resourceType}/{resourceId}")
    @Operation(operationId = "revokeAccess", summary = "Remove a grant (admin)")
    public ResponseEntity<Void> revokeGrant(@PathVariable String ws, @PathVariable UUID id, @PathVariable String resourceType,
                                            @PathVariable UUID resourceId) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            if (!store.revokeGrant(id, resourceType, resourceId)) {
                throw ApiProblem.notFound("grant_not_found", "That grant does not exist.");
            }
            audit.record(wsId, granted.user().id(), "grant.remove", "service_account", id.toString(),
                    Map.of("resourceType", resourceType, "resourceId", resourceId.toString()));
        });
        authorizer.invalidate(wsId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/endpoints/{endpointId}/access")
    @Operation(operationId = "listEndpointAccess", summary = "Service accounts that may call the endpoint, directly or through its group")
    public List<EndpointAccess> endpointAccess(@PathVariable String ws, @PathVariable UUID endpointId) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> store.endpointAccess(endpointId));
    }

    private ServiceAccountDetail detail(UUID id) {
        ServiceAccount sa = store.find(id).orElseThrow(ServiceAccountController::notFound);
        return new ServiceAccountDetail(sa, store.clients(id), store.grants(id));
    }

    private static ApiProblem notFound() {
        return ApiProblem.notFound("service_account_not_found", "That service account does not exist.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
