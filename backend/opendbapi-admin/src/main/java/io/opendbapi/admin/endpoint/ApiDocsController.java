package io.opendbapi.admin.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.docs.DocsStore;
import io.opendbapi.core.docs.OpenApiBuilder;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** OpenAPI documents for members: every live endpoint, private ones included (spec §10). */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "endpoints")
public class ApiDocsController {

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final DocsStore docs;
    private final OpenDbApiProperties properties;
    private final ObjectMapper json;

    public ApiDocsController(WorkspaceAccess access, WorkspaceScope scope, DocsStore docs, OpenDbApiProperties properties,
                             ObjectMapper json) {
        this.access = access;
        this.scope = scope;
        this.docs = docs;
        this.properties = properties;
        this.json = json;
    }

    @GetMapping("/openapi.json")
    @Operation(operationId = "workspaceOpenApi", summary = "OpenAPI 3.1 document of every live endpoint",
            responses = @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(type = "object"))))
    public ResponseEntity<ObjectNode> workspace(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        List<DocsStore.DocEntry> entries = scope.call(granted.workspace().id(), () -> docs.live(null));
        return download(build(granted.workspace(), entries), ws + "-openapi.json");
    }

    @GetMapping("/endpoints/{id}/openapi.json")
    @Operation(operationId = "endpointOpenApi", summary = "OpenAPI 3.1 document of one endpoint's live version",
            responses = @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(type = "object"))))
    public ResponseEntity<ObjectNode> endpoint(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        List<DocsStore.DocEntry> entries = scope.call(granted.workspace().id(), () -> docs.live(id));
        if (entries.isEmpty()) {
            throw ApiProblem.conflict("not_published", "The endpoint has no live version to document.");
        }
        return download(build(granted.workspace(), entries), ws + "-" + entries.get(0).snapshot().path().replaceAll("[^A-Za-z0-9_-]+", "-")
                + "-openapi.json");
    }

    private ObjectNode build(Workspace workspace, List<DocsStore.DocEntry> entries) {
        String base = properties.serving().urlBase();
        if (base.isBlank()) {
            base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return OpenApiBuilder.build(new OpenApiBuilder.Target(workspace.name() + " API",
                base + "/" + properties.serving().context() + "/" + workspace.slug(), base + "/oauth2/token"), entries, json);
    }

    private static ResponseEntity<ObjectNode> download(ObjectNode doc, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(doc);
    }
}
