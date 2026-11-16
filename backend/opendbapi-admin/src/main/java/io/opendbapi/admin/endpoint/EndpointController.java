package io.opendbapi.admin.endpoint;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointService;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.EndpointValidator;
import io.opendbapi.core.endpoint.EndpointVersion;
import io.opendbapi.core.endpoint.QueryExecutor;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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

/** Endpoint authoring and lifecycle (spec §5, §11.1, §21.2). Viewers read, authors change and publish. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "endpoints")
public class EndpointController {

    private static final String PATH_REGEX = "^[A-Za-z0-9_\\-{}]+(/[A-Za-z0-9_\\-{}]+)*$";
    private static final String PATH_MESSAGE = "must be segments of letters, digits, _ - or {param}, separated by /, without a leading /";

    public record EndpointSummary(
            UUID id,
            String name,
            @Schema(nullable = true) String description,
            String method,
            String path,
            @Schema(allowableValues = {"draft", "live", "disabled"}) String state,
            @Schema(nullable = true) Integer liveVersion,
            boolean dirty,
            @Schema(allowableValues = {"private", "public"}) String access,
            @Schema(nullable = true) UUID groupId,
            @Schema(nullable = true) UUID datasourceId,
            boolean trashed,
            OffsetDateTime updatedAt) {

        static EndpointSummary of(Endpoint e) {
            return new EndpointSummary(e.id(), e.name(), e.description(), e.method(), e.path(), e.state(), e.liveVersion(),
                    e.dirty(), e.access(), e.groupId(), e.definition().task().datasourceId(), e.trashed(), e.updatedAt());
        }
    }

    public record EndpointDetail(EndpointSummary summary, EndpointDefinition definition, OffsetDateTime createdAt) {
        static EndpointDetail of(Endpoint e) {
            return new EndpointDetail(EndpointSummary.of(e), e.definition(), e.createdAt());
        }
    }

    public record CreateEndpoint(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank @Size(max = 200) @Pattern(regexp = PATH_REGEX, message = PATH_MESSAGE) String path,
            @Schema(nullable = true) UUID groupId,
            @NotBlank @Pattern(regexp = "private|public") String access,
            @NotNull UUID datasourceId) {
    }

    public record UpdateEndpoint(
            @NotBlank @Size(max = 120) String name,
            @Schema(nullable = true) @Size(max = 2000) String description,
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank @Size(max = 200) @Pattern(regexp = PATH_REGEX, message = PATH_MESSAGE) String path,
            @Schema(nullable = true) UUID groupId,
            @NotBlank @Pattern(regexp = "private|public") String access,
            @NotNull EndpointDefinition definition) {
    }

    public record PublishRequest(@Schema(nullable = true) @Size(max = 500) String note) {
    }

    public record ValidationView(boolean ok, List<EndpointValidator.Finding> findings, List<String> detectedParameters,
                                 List<QueryExecutor.Column> outputColumns) {
        static ValidationView of(EndpointValidator.Validation v) {
            return new ValidationView(v.ok(), v.findings(), v.detectedParameters(), v.outputColumns());
        }
    }

    public record VersionView(
            int version,
            @Schema(nullable = true) String note,
            @Schema(nullable = true) String publishedBy,
            OffsetDateTime publishedAt,
            boolean live,
            String method,
            String path,
            String access,
            EndpointDefinition definition) {
    }

    public record PreviewRequest(
            @NotBlank @Pattern(regexp = "GET|POST|PUT|DELETE") String method,
            @NotBlank String path,
            @NotNull EndpointDefinition definition,
            @Schema(nullable = true, description = "Test values by parameter name") Map<String, Object> params,
            @Schema(nullable = true) Integer page,
            @Schema(nullable = true) Integer size) {
    }

    public record ExecutedSql(String sql, List<Object> parameters) {
    }

    public record PreviewResponse(
            List<QueryExecutor.Column> columns,
            List<Map<String, Object>> rows,
            @Schema(nullable = true) Long affectedRows,
            boolean truncated,
            @Schema(nullable = true) Long total,
            @Schema(nullable = true) Boolean hasNext,
            long durationMs,
            List<ExecutedSql> executed,
            List<EndpointValidator.Finding> findings) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final EndpointStore store;
    private final EndpointService endpoints;
    private final AuditLog audit;

    public EndpointController(WorkspaceAccess access, WorkspaceScope scope, EndpointStore store, EndpointService endpoints,
                              AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.store = store;
        this.endpoints = endpoints;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listEndpoints", summary = "Endpoints, optionally filtered; trashed=true lists the trash")
    public List<EndpointSummary> list(@PathVariable String ws,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) UUID groupId,
                                      @RequestParam(required = false) UUID datasourceId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "false") boolean trashed) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> store.list(new EndpointStore.Filter(state, groupId, datasourceId, q, trashed)))
                .stream().map(EndpointSummary::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createEndpoint", summary = "Create a draft with a starter query (author)")
    public EndpointDetail create(@PathVariable String ws, @Valid @RequestBody CreateEndpoint body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Endpoint created = endpoints.create(granted.workspace().id(), new EndpointService.Draft(body.groupId(), body.name().strip(),
                null, body.method(), body.path(), body.access(), EndpointDefinition.starter(body.datasourceId())), granted.user().id());
        audit(granted, "endpoint.create", created, Map.of("route", created.method() + " /" + created.path()));
        return EndpointDetail.of(created);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getEndpoint", summary = "The draft and what is live")
    public EndpointDetail get(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return EndpointDetail.of(endpoints.get(granted.workspace().id(), id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateEndpoint", summary = "Save the draft; callers keep getting the live version (author)")
    public EndpointDetail update(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody UpdateEndpoint body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Endpoint updated = endpoints.update(granted.workspace().id(), id, new EndpointService.Draft(body.groupId(), body.name().strip(),
                body.description(), body.method(), body.path(), body.access(), body.definition()));
        return EndpointDetail.of(updated);
    }

    @PostMapping("/{id}:validate")
    @Operation(operationId = "validateEndpoint", summary = "Check the saved draft as publish would (author)")
    public ValidationView validate(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        return ValidationView.of(endpoints.validate(granted.workspace().id(), id));
    }

    @PostMapping("/{id}:publish")
    @Operation(operationId = "publishEndpoint", summary = "Freeze the draft as a new version and serve it (author)")
    public VersionView publish(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody(required = false) PublishRequest body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        EndpointVersion v = endpoints.publish(granted.workspace().id(), id, body == null ? null : body.note(), granted.user().id());
        audit.record(granted.workspace().id(), granted.user().id(), "endpoint.publish", "endpoint", id.toString(),
                Map.of("version", v.version(), "route", v.snapshot().method() + " /" + v.snapshot().path()));
        return view(v, true);
    }

    @PostMapping("/{id}:disable")
    @Operation(operationId = "disableEndpoint", summary = "Stop serving (author)")
    public EndpointSummary disable(@PathVariable String ws, @PathVariable UUID id) {
        return lifecycle(ws, id, "endpoint.disable", endpoints::disable);
    }

    @PostMapping("/{id}:enable")
    @Operation(operationId = "enableEndpoint", summary = "Serve the live version again (author)")
    public EndpointSummary enable(@PathVariable String ws, @PathVariable UUID id) {
        return lifecycle(ws, id, "endpoint.enable", endpoints::enable);
    }

    @PostMapping("/{id}:trash")
    @Operation(operationId = "trashEndpoint", summary = "Move a draft or disabled endpoint to the trash (author)")
    public EndpointSummary trash(@PathVariable String ws, @PathVariable UUID id) {
        return lifecycle(ws, id, "endpoint.trash", endpoints::trash);
    }

    @PostMapping("/{id}:restore")
    @Operation(operationId = "restoreEndpoint", summary = "Bring back from the trash, disabled (author)")
    public EndpointSummary restore(@PathVariable String ws, @PathVariable UUID id) {
        return lifecycle(ws, id, "endpoint.restore", endpoints::restore);
    }

    @PostMapping("/{id}:duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "duplicateEndpoint", summary = "Copy the draft as a new draft (author)")
    public EndpointDetail duplicate(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Endpoint copy = endpoints.duplicate(granted.workspace().id(), id, granted.user().id());
        audit(granted, "endpoint.create", copy, Map.of("copiedFrom", id.toString()));
        return EndpointDetail.of(copy);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteEndpoint", summary = "Delete for good; only from the trash (author)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        endpoints.delete(granted.workspace().id(), id);
        audit.record(granted.workspace().id(), granted.user().id(), "endpoint.delete", "endpoint", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    @Operation(operationId = "listEndpointVersions", summary = "Published versions, newest first")
    public List<VersionView> versions(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        Endpoint e = endpoints.get(granted.workspace().id(), id);
        return endpoints.versions(granted.workspace().id(), id).stream()
                .map(v -> view(v, v.id().equals(e.liveVersionId()))).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    @Operation(operationId = "getEndpointVersion", summary = "One published version")
    public VersionView version(@PathVariable String ws, @PathVariable UUID id, @PathVariable int version) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        Endpoint e = endpoints.get(granted.workspace().id(), id);
        EndpointVersion v = endpoints.version(granted.workspace().id(), id, version);
        return view(v, v.id().equals(e.liveVersionId()));
    }

    @PostMapping("/{id}/versions/{version}:rollback")
    @Operation(operationId = "rollbackEndpoint", summary = "Serve an older version; the draft is untouched (author)")
    public EndpointSummary rollback(@PathVariable String ws, @PathVariable UUID id, @PathVariable int version) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Endpoint e = endpoints.rollback(granted.workspace().id(), id, version);
        audit(granted, "endpoint.rollback", e, Map.of("version", version));
        return EndpointSummary.of(e);
    }

    @PostMapping("/{id}/preview")
    @Operation(operationId = "previewEndpoint", summary = "Run the on-screen draft with test values; always rolled back (author)")
    public PreviewResponse preview(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody PreviewRequest body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        endpoints.get(granted.workspace().id(), id);
        EndpointService.Preview p = endpoints.preview(granted.workspace().id(), body.method(), body.path(), body.definition(),
                body.params(), body.page(), body.size());
        QueryExecutor.Result r = p.result();
        return new PreviewResponse(r.columns(), r.rows(), r.affectedRows(), r.truncated(), r.total(), r.hasNext(), r.durationMs(),
                r.executed().stream().map(b -> new ExecutedSql(b.sql(), b.parameters())).toList(), p.findings());
    }

    private EndpointSummary lifecycle(String ws, UUID id, String action,
                                      java.util.function.BiFunction<UUID, UUID, Endpoint> change) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Endpoint e = change.apply(granted.workspace().id(), id);
        audit(granted, action, e, Map.of());
        return EndpointSummary.of(e);
    }

    private void audit(WorkspaceAccess.Granted granted, String action, Endpoint e, Map<String, ?> detail) {
        Map<String, Object> all = new java.util.HashMap<>(detail);
        all.put("name", e.name());
        audit.record(granted.workspace().id(), granted.user().id(), action, "endpoint", e.id().toString(), all);
    }

    private static VersionView view(EndpointVersion v, boolean live) {
        EndpointVersion.Snapshot s = v.snapshot();
        return new VersionView(v.version(), v.note(), v.publishedByName(), v.publishedAt(), live, s.method(), s.path(),
                s.access(), s.definition());
    }
}
