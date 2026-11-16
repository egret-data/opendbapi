package io.opendbapi.admin.audit;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.admin.security.CurrentUser;
import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/admin/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "audit")
public class AuditController {

    public record AuditEntry(
            long id,
            OffsetDateTime ts,
            @Schema(nullable = true) UUID workspaceId,
            @Schema(nullable = true) String username,
            String action,
            @Schema(nullable = true) String targetType,
            @Schema(nullable = true) String targetId,
            JsonNode detail) {

        static AuditEntry of(AuditLog.Entry e) {
            return new AuditEntry(e.id(), e.ts(), e.workspaceId(), e.username(), e.action(), e.targetType(),
                    e.targetId(), e.detail());
        }
    }

    private static final int MAX_PAGE = 200;

    private final CurrentUser currentUser;
    private final WorkspaceAccess access;
    private final AuditLog audit;

    public AuditController(CurrentUser currentUser, WorkspaceAccess access, AuditLog audit) {
        this.currentUser = currentUser;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/audit")
    @Operation(operationId = "list", summary = "System-wide audit log, newest first (system admin)")
    public List<AuditEntry> list(@RequestParam(required = false) String action,
                                 @RequestParam(required = false) Long before,
                                 @RequestParam(defaultValue = "50") int limit) {
        currentUser.requireSystemAdmin();
        return audit.list(null, action, Math.min(Math.max(limit, 1), MAX_PAGE), before).stream().map(AuditEntry::of).toList();
    }

    @GetMapping("/ws/{ws}/audit")
    @Operation(operationId = "listForWorkspace", summary = "One workspace's audit log (workspace admin)")
    public List<AuditEntry> listForWorkspace(@PathVariable String ws,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "50") int limit) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        return audit.list(granted.workspace().id(), action, Math.min(Math.max(limit, 1), MAX_PAGE), before).stream()
                .map(AuditEntry::of).toList();
    }
}
