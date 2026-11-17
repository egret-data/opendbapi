package io.opendbapi.admin.alert;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.alert.AlertChannelStore;
import io.opendbapi.core.alert.AlertChannelStore.AlertChannel;
import io.opendbapi.core.alert.Alerts;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
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

/** Webhooks that hear about failing endpoints (spec §13.2). Members read; workspace admins change. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/alert-channels", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "alert-channels")
public class AlertChannelController {

    /** @param url required on create; on change, null keeps the stored URL */
    public record SaveAlertChannel(@NotBlank @Size(max = 120) String name,
                                   @NotBlank @Schema(allowableValues = {"slack", "teams", "google_chat", "webhook"}) String kind,
                                   @Schema(nullable = true, description = "Write-only; never returned") @Size(max = 2000) String url,
                                   boolean enabled) {
    }

    public record AlertTestResult(boolean ok, @Schema(nullable = true) Integer status, @Schema(nullable = true) String error) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final AlertChannelStore store;
    private final Alerts alerts;
    private final AuditLog audit;

    public AlertChannelController(WorkspaceAccess access, WorkspaceScope scope, AlertChannelStore store, Alerts alerts, AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.store = store;
        this.alerts = alerts;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listAlertChannels", summary = "Alert channels of the workspace")
    public List<AlertChannel> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), store::list);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createAlertChannel", summary = "Add a webhook (admin)")
    public AlertChannel create(@PathVariable String ws, @Valid @RequestBody SaveAlertChannel body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        String kind = kind(body.kind());
        if (body.url() == null || body.url().isBlank()) {
            throw ApiProblem.badRequest("invalid_url", "Give the webhook URL.");
        }
        String host = alerts.checkUrl(body.url());
        String ref = alerts.seal(body.url());
        return scope.call(wsId, () -> {
            UUID id = store.create(wsId, body.name().strip(), kind, ref, host, body.enabled(), granted.user().id());
            audit.record(wsId, granted.user().id(), "alert_channel.create", "alert_channel", id.toString(),
                    Map.of("name", body.name().strip(), "kind", kind, "host", host));
            return store.find(id).orElseThrow();
        });
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateAlertChannel", summary = "Rename, change, enable or disable a webhook (admin)")
    public AlertChannel update(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveAlertChannel body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        String kind = kind(body.kind());
        boolean newUrl = body.url() != null && !body.url().isBlank();
        String host = newUrl ? alerts.checkUrl(body.url()) : null;
        String ref = newUrl ? alerts.seal(body.url()) : null;
        return scope.call(wsId, () -> {
            if (!store.update(id, body.name().strip(), kind, ref, host, body.enabled())) {
                throw notFound();
            }
            audit.record(wsId, granted.user().id(), "alert_channel.update", "alert_channel", id.toString(),
                    Map.of("name", body.name().strip(), "kind", kind, "enabled", body.enabled(), "urlChanged", newUrl));
            return store.find(id).orElseThrow();
        });
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteAlertChannel", summary = "Remove a webhook (admin)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        scope.run(wsId, () -> {
            AlertChannel c = store.find(id).orElseThrow(AlertChannelController::notFound);
            store.delete(id);
            audit.record(wsId, granted.user().id(), "alert_channel.delete", "alert_channel", id.toString(), Map.of("name", c.name()));
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}:test")
    @Operation(operationId = "testAlertChannel", summary = "Send a test message now (admin)")
    public AlertTestResult test(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        AlertChannelStore.ChannelTarget target = scope.call(wsId, () -> store.target(id)).orElseThrow(AlertChannelController::notFound);
        Alerts.Delivery d = alerts.test(wsId, target, granted.workspace().slug());
        return new AlertTestResult(d.ok(), d.status(), d.error());
    }

    private static String kind(String kind) {
        if (!Alerts.KINDS.contains(kind)) {
            throw ApiProblem.badRequest("invalid_kind", "kind must be slack, teams, google_chat or webhook.");
        }
        return kind;
    }

    private static ApiProblem notFound() {
        return ApiProblem.notFound("alert_channel_not_found", "That alert channel does not exist.");
    }
}
