package io.opendbapi.admin.transfer;

import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.transfer.ConfigPlanner;
import io.opendbapi.core.transfer.EnvironmentStore;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration as code (spec §15): environments, and plan/apply of a YAML file in the export
 * format. Workspace admins only. {@code format=text} answers in plain text for the CLI, with
 * the outcome in {@code X-OpenDBAPI-*} headers.
 */
@RestController
@RequestMapping("/admin/api/ws/{ws}")
@Tag(name = "config")
public class ConfigController {

    public record PutEnvironment(@Schema(description = "Logical datasource name in configuration files → datasource name here")
                                 Map<String, String> bindings) {
    }

    private static final String YAML_TYPES = "application/yaml";

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final EnvironmentStore environments;
    private final ConfigPlanner planner;
    private final AuditLog audit;

    public ConfigController(WorkspaceAccess access, WorkspaceScope scope, EnvironmentStore environments, ConfigPlanner planner,
                            AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.environments = environments;
        this.planner = planner;
        this.audit = audit;
    }

    @GetMapping("/environments")
    @Operation(operationId = "listEnvironments", summary = "Environments and their datasource bindings (admin)")
    public List<EnvironmentStore.ConfigEnvironment> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        return scope.call(granted.workspace().id(), environments::list);
    }

    @PutMapping("/environments/{name}")
    @Operation(operationId = "putEnvironment", summary = "Create or replace an environment (admin)")
    public EnvironmentStore.ConfigEnvironment put(@PathVariable String ws, @PathVariable String name, @RequestBody PutEnvironment body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        var wsId = granted.workspace().id();
        var saved = scope.call(wsId, () -> {
            environments.put(wsId, name, body.bindings());
            return environments.find(name).orElseThrow();
        });
        audit.record(wsId, granted.user().id(), "environment.put", "environment", name, Map.of("bindings", saved.bindings()));
        return saved;
    }

    @DeleteMapping("/environments/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteEnvironment", summary = "Delete an environment (admin)")
    public void delete(@PathVariable String ws, @PathVariable String name) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        var wsId = granted.workspace().id();
        if (!scope.call(wsId, () -> environments.delete(name))) {
            throw ApiProblem.notFound("environment_not_found", "No environment named '%s'.".formatted(name));
        }
        audit.record(wsId, granted.user().id(), "environment.delete", "environment", name, Map.of());
    }

    @PostMapping(path = "/config:plan", consumes = {YAML_TYPES, "text/yaml", MediaType.TEXT_PLAIN_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @Operation(operationId = "planConfig", summary = "Compare a YAML file with the workspace without changing anything (admin)",
            responses = @ApiResponse(responseCode = "200", description = "format=json: the plan; format=text: the plan for a terminal",
                    content = {@Content(mediaType = "application/json", schema = @Schema(implementation = ConfigPlanner.ConfigPlan.class)),
                            @Content(mediaType = "text/plain", schema = @Schema(type = "string"))}),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = YAML_TYPES,
                    schema = @Schema(type = "string"))))
    public ResponseEntity<?> plan(@PathVariable String ws, @RequestBody String file,
                                  @Parameter(description = "Maps logical datasource names") @RequestParam(required = false) String environment,
                                  @Parameter(description = "Delete what the file does not list") @RequestParam(defaultValue = "false") boolean prune,
                                  @Parameter(description = "Publish endpoints whose metadata.state is live, disable those marked disabled")
                                  @RequestParam(defaultValue = "false") boolean publish,
                                  @RequestParam(defaultValue = "json") String format) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        var plan = planner.plan(granted.workspace().id(), file, new ConfigPlanner.PlanOptions(blank(environment), prune, publish));
        if ("text".equals(format)) {
            return text(PlanText.plan(ws, plan), plan, true);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(plan);
    }

    @PostMapping(path = "/config:apply", consumes = {YAML_TYPES, "text/yaml", MediaType.TEXT_PLAIN_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @Operation(operationId = "applyConfig",
            summary = "Make the workspace match a YAML file in one transaction, then publish endpoints after their tests (admin)",
            responses = @ApiResponse(responseCode = "200", description = "format=json: what was done; format=text: the same for a terminal",
                    content = {@Content(mediaType = "application/json", schema = @Schema(implementation = ConfigPlanner.ConfigApplyResult.class)),
                            @Content(mediaType = "text/plain", schema = @Schema(type = "string"))}),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = YAML_TYPES,
                    schema = @Schema(type = "string"))))
    public ResponseEntity<?> apply(@PathVariable String ws, @RequestBody String file,
                                   @RequestParam(required = false) String environment,
                                   @RequestParam(defaultValue = "false") boolean prune,
                                   @RequestParam(defaultValue = "false") boolean publish,
                                   @Parameter(description = "Refuse with 409 plan_changed unless the plan still has this digest")
                                   @RequestParam(required = false) String expectedDigest,
                                   @RequestParam(defaultValue = "json") String format) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        var wsId = granted.workspace().id();
        var result = planner.apply(wsId, file, new ConfigPlanner.PlanOptions(blank(environment), prune, publish), expectedDigest,
                granted.user().id());
        audit.record(wsId, granted.user().id(), "config.apply", "workspace", wsId.toString(), Map.of(
                "environment", String.valueOf(environment), "counts", result.plan().counts(), "digest", result.plan().digest(),
                "published", result.published().size()));
        if ("text".equals(format)) {
            return text(PlanText.apply(ws, result), result.plan(), result.ok());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
    }

    private static ResponseEntity<String> text(String body, ConfigPlanner.ConfigPlan plan, boolean ok) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .header("X-OpenDBAPI-Changes", String.valueOf(plan.hasChanges()))
                .header("X-OpenDBAPI-Digest", plan.digest())
                .header("X-OpenDBAPI-Result", ok ? "ok" : "failed")
                .body(body);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
