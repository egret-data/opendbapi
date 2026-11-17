package io.opendbapi.admin.transfer;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.endpoint.EndpointRegistry;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.transfer.DbapiImporter;
import io.opendbapi.core.transfer.YamlExporter;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workspace export as YAML (spec §15.1) and import of DBAPI exports (spec §15.3). Workspace admins only. */
@RestController
@RequestMapping("/admin/api/ws/{ws}")
@Tag(name = "transfer")
public class TransferController {

    private final WorkspaceAccess access;
    private final YamlExporter exporter;
    private final DbapiImporter importer;
    private final EndpointRegistry registry;
    private final AuditLog audit;

    public TransferController(WorkspaceAccess access, YamlExporter exporter, DbapiImporter importer, EndpointRegistry registry,
                              AuditLog audit) {
        this.access = access;
        this.exporter = exporter;
        this.importer = importer;
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping("/export")
    @Operation(operationId = "exportWorkspace", summary = "The workspace as YAML documents, without secrets (admin)",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/yaml",
                    schema = @Schema(type = "string"))))
    public ResponseEntity<byte[]> export(@PathVariable String ws, @RequestParam(defaultValue = "yaml") String format) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        if (!"yaml".equals(format)) {
            throw ApiProblem.badRequest("invalid_format", "Only format=yaml is supported.");
        }
        byte[] body = exporter.export(granted.workspace().id()).getBytes(StandardCharsets.UTF_8);
        audit.record(granted.workspace().id(), granted.user().id(), "workspace.export", "workspace", granted.workspace().id().toString(),
                Map.of("format", format));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ws + ".yaml\"")
                .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                .body(body);
    }

    @PostMapping(path = "/import/dbapi", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "importDbapi",
            summary = "Import DBAPI's api_config.json, group.json and datasource.json; dryRun reports without writing (admin)")
    public DbapiImporter.ImportReport importDbapi(@PathVariable String ws, @RequestParam(defaultValue = "true") boolean dryRun,
                                                  @RequestBody DbapiImporter.DbapiExport body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        DbapiImporter.ImportReport report = importer.importExport(granted.workspace().id(), body, dryRun, granted.user().id());
        if (!dryRun) {
            audit.record(granted.workspace().id(), granted.user().id(), "workspace.import_dbapi", "workspace",
                    granted.workspace().id().toString(), Map.of("created", report.created(), "skipped", report.skipped()));
            registry.invalidate(granted.workspace().id());
        }
        return report;
    }
}
