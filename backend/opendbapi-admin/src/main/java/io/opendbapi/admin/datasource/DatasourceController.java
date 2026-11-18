package io.opendbapi.admin.datasource;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.datasource.JdbcConfig;
import io.opendbapi.core.datasource.JdbcDialect;
import io.opendbapi.core.datasource.HttpConfig;
import io.opendbapi.core.datasource.HttpSources;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.SecretResolver;
import io.opendbapi.core.secret.WorkspaceSecrets;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace datasources (spec §4.6, §21.2). Viewers see them, authors browse their tables and
 * test them, admins create, change and delete them. The password is write-only.
 */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/datasources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "datasources")
public class DatasourceController {

    public record PoolSettings(
            @Schema(nullable = true, description = "1–50, default 10") Integer maximumPoolSize,
            @Schema(nullable = true, description = "default 0") Integer minimumIdle,
            @Schema(nullable = true, description = "250–60000 ms, default 10000") Integer connectionTimeoutMs) {
    }

    public record DatasourceView(
            UUID id,
            String name,
            @Schema(nullable = true) String description,
            @Schema(allowableValues = {"jdbc", "http"}) String type,
            @Schema(nullable = true, description = "Databases only") JdbcDialect dialect,
            String host,
            int port,
            @Schema(nullable = true, description = "Databases only") String database,
            @Schema(nullable = true, description = "Databases only") String username,
            Map<String, String> properties,
            PoolSettings pool,
            boolean readOnly,
            boolean hasPassword,
            @Schema(nullable = true, description = "The env: or vault: reference when the password comes from outside") String passwordEnv,
            @Schema(nullable = true, description = "The managed secret holding the password, when it is one") String passwordSecret,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            @Schema(nullable = true, description = "Last connection test; null when never tested since the settings changed")
            DatasourceStore.LastTest lastTest,
            @Schema(nullable = true, description = "HTTP APIs only") HttpConfig http) {

        static DatasourceView of(Datasource d) {
            return of(d, null);
        }

        static DatasourceView of(Datasource d, DatasourceStore.LastTest lastTest) {
            String ref = d.secretRef();
            String passwordEnv = ref != null && (ref.startsWith("env:") || ref.startsWith("vault:")) ? ref : null;
            String passwordSecret = WorkspaceSecrets.isReference(ref) ? WorkspaceSecrets.nameOf(ref) : null;
            if (d.isHttp()) {
                java.net.URI uri = d.http().uri();
                int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                return new DatasourceView(d.id(), d.name(), d.description(), Datasource.HTTP, null, uri.getHost(), port, null, null,
                        Map.of(), new PoolSettings(null, null, null), d.readOnly(), ref != null, passwordEnv, passwordSecret,
                        d.createdAt(), d.updatedAt(), lastTest, d.http());
            }
            JdbcConfig c = d.config();
            return new DatasourceView(d.id(), d.name(), d.description(), Datasource.JDBC, c.dialect(), c.host(), c.effectivePort(),
                    c.database(), c.username(), c.properties(),
                    new PoolSettings(c.pool().maximumPoolSize(), c.pool().minimumIdle(), c.pool().connectionTimeoutMs()),
                    d.readOnly(), ref != null, passwordEnv, passwordSecret, d.createdAt(), d.updatedAt(), lastTest, null);
        }
    }

    public record SaveDatasource(
            @NotBlank @Size(max = 120) String name,
            @Schema(nullable = true) @Size(max = 1000) String description,
            @Schema(nullable = true, description = "Databases: required") JdbcDialect dialect,
            @Schema(nullable = true, description = "Databases: required") String host,
            @Schema(nullable = true, description = "Default port of the dialect when empty") Integer port,
            @Schema(nullable = true, description = "Databases: required") String database,
            @Schema(nullable = true, description = "Databases: required") String username,
            @Schema(nullable = true, description = "Plain password, token or API key (stored encrypted), or a secret:, env: or vault: "
                    + "reference; omit to keep the stored one")
            String password,
            @Schema(nullable = true) Map<String, String> properties,
            @Schema(nullable = true) PoolSettings pool,
            boolean readOnly,
            @Schema(nullable = true, allowableValues = {"jdbc", "http"}, description = "Default jdbc") String type,
            @Schema(nullable = true, description = "HTTP APIs: required") HttpConfig http) {

        boolean isHttp() {
            return Datasource.HTTP.equals(type);
        }

        String kind() {
            return isHttp() ? Datasource.HTTP : Datasource.JDBC;
        }

        /** The validated settings: an {@link HttpConfig} or a {@link JdbcConfig}. */
        Object settings() {
            if (isHttp()) {
                if (http == null) {
                    throw ApiProblem.badRequest("invalid_datasource", "http settings are required for an HTTP datasource.");
                }
                return http.validate();
            }
            return config();
        }

        JdbcConfig config() {
            if (type != null && !Datasource.JDBC.equals(type)) {
                throw ApiProblem.badRequest("invalid_datasource", "type is jdbc or http.");
            }
            if (dialect == null || host == null || host.isBlank() || database == null || database.isBlank() || username == null
                    || username.isBlank()) {
                throw ApiProblem.badRequest("invalid_datasource", "A database needs dialect, host, database and username.");
            }
            PoolSettings p = pool == null ? new PoolSettings(null, null, null) : pool;
            return new JdbcConfig(dialect, host.strip(), port, database.strip(), username.strip(), properties,
                    new JdbcConfig.Pool(p.maximumPoolSize(), p.minimumIdle(), p.connectionTimeoutMs())).validate();
        }

        /** Whether a new datasource of these settings may have no secret. */
        boolean secretOptional() {
            return isHttp() ? "none".equals(http.auth()) : dialect.passwordOptional();
        }
    }

    public record TestDatasource(
            @NotNull @Valid SaveDatasource settings,
            @Schema(nullable = true, description = "Use this saved datasource's password when settings.password is empty")
            UUID existingId) {
    }

    public record DatasourceTestResult(
            boolean ok,
            String message,
            @Schema(nullable = true) String product,
            @Schema(nullable = true) String version,
            @Schema(nullable = true) Long latencyMs) {
    }

    public record DialectInfo(
            JdbcDialect dialect,
            int defaultPort,
            List<String> allowedProperties,
            @Schema(description = "Whether a rollback undoes writes: preview and transactions need it") boolean transactional,
            @Schema(description = "Whether a datasource may have no password") boolean passwordOptional) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final DatasourceStore store;
    private final DataSourceRegistry registry;
    private final SecretResolver secrets;
    private final AuditLog audit;
    private WorkspaceSecrets workspaceSecrets;

    @org.springframework.beans.factory.annotation.Autowired
    void setWorkspaceSecrets(WorkspaceSecrets workspaceSecrets) {
        this.workspaceSecrets = workspaceSecrets;
    }

    private HttpSources httpSources;

    @org.springframework.beans.factory.annotation.Autowired
    void setHttpSources(HttpSources httpSources) {
        this.httpSources = httpSources;
    }

    public DatasourceController(WorkspaceAccess access, WorkspaceScope scope, DatasourceStore store,
                                DataSourceRegistry registry, SecretResolver secrets, AuditLog audit) {
        this.access = access;
        this.scope = scope;
        this.store = store;
        this.registry = registry;
        this.secrets = secrets;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listDatasources", summary = "Datasources of the workspace (any member)")
    public List<DatasourceView> list(@PathVariable String ws) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> {
            var tests = store.lastTests();
            return store.list().stream().map(d -> DatasourceView.of(d, tests.get(d.id()))).toList();
        });
    }

    @GetMapping("/dialects")
    @Operation(operationId = "listDialects", summary = "Supported databases, default ports and allowed driver properties")
    public List<DialectInfo> dialects(@PathVariable String ws) {
        access.require(ws, WorkspaceRole.VIEWER);
        return java.util.Arrays.stream(JdbcDialect.values())
                .map(d -> new DialectInfo(d, d.defaultPort(), d.allowedProperties().stream().sorted().toList(), d.transactional(),
                        d.passwordOptional()))
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getDatasource", summary = "One datasource (any member)")
    public DatasourceView get(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        UUID wsId = granted.workspace().id();
        return scope.call(wsId, () -> {
            Datasource d = store.find(id).orElseThrow(() -> ApiProblem.notFound("datasource_not_found",
                    "That datasource does not exist in this workspace."));
            return DatasourceView.of(d, store.lastTests().get(id));
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDatasource", summary = "Add a datasource (workspace admin)")
    public DatasourceView create(@PathVariable String ws, @Valid @RequestBody SaveDatasource body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        Object config = body.settings();
        boolean noPassword = body.password() == null || body.password().isEmpty();
        if (noPassword && !body.secretOptional()) {
            throw ApiProblem.badRequest("invalid_datasource", body.isHttp()
                    ? "This authentication needs a secret: a token, password or API key." : "password is required for a new datasource.");
        }
        UUID wsId = granted.workspace().id();
        String secretRef = noPassword ? null : scope.call(wsId, () -> workspaceSecrets.toReference(body.password()));
        Datasource created = scope.call(wsId, () -> {
            Object quota = granted.workspace().quotas().get("datasources");
            if (quota instanceof Number max && store.count() >= max.intValue()) {
                throw ApiProblem.conflict("quota_exceeded",
                        "This workspace may have at most %d datasources.".formatted(max.intValue()));
            }
            return store.create(wsId, body.name().strip(), body.description(), body.kind(), config, secretRef, body.readOnly(),
                    granted.user().id());
        });
        audit.record(wsId, granted.user().id(), "datasource.create", "datasource", created.id().toString(),
                Map.of("name", created.name(), "dialect", config instanceof JdbcConfig j ? j.dialect().name() : "HTTP",
                        "host", config instanceof JdbcConfig j ? j.host() : ((HttpConfig) config).host()));
        return DatasourceView.of(created);
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateDatasource", summary = "Change a datasource; omit password to keep it (workspace admin)")
    public DatasourceView update(@PathVariable String ws, @PathVariable UUID id, @Valid @RequestBody SaveDatasource body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        Object config = body.settings();
        UUID wsId = granted.workspace().id();
        String secretRef = body.password() == null || body.password().isEmpty() ? null
                : scope.call(wsId, () -> workspaceSecrets.toReference(body.password()));
        Datasource updated = scope.call(wsId, () -> {
            if (!find(wsId, id).type().equals(body.kind())) {
                throw ApiProblem.badRequest("invalid_datasource", "A datasource keeps its type; add a new one instead.");
            }
            return store.update(id, body.name().strip(), body.description(), config, secretRef, body.readOnly());
        });
        registry.evict(id);
        Map<String, Object> detail = new HashMap<>();
        detail.put("name", updated.name());
        detail.put("readOnly", updated.readOnly());
        detail.put("passwordChanged", secretRef != null);
        audit.record(wsId, granted.user().id(), "datasource.update", "datasource", id.toString(), detail);
        return DatasourceView.of(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteDatasource", summary = "Delete a datasource no endpoint uses (workspace admin)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        UUID wsId = granted.workspace().id();
        String name = scope.call(wsId, () -> {
            Datasource ds = find(wsId, id);
            List<String> users = store.endpointsUsing(id);
            if (!users.isEmpty()) {
                throw ApiProblem.conflict("datasource_in_use",
                        "Endpoints still use this datasource: %s.".formatted(String.join(", ", users)));
            }
            store.delete(id);
            return ds.name();
        });
        registry.evict(id);
        audit.record(wsId, granted.user().id(), "datasource.delete", "datasource", id.toString(), Map.of("name", name));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @Operation(operationId = "testDatasourceSettings", summary = "Try settings before saving them (workspace admin)")
    public DatasourceTestResult testSettings(@PathVariable String ws, @Valid @RequestBody TestDatasource body) {
        var granted = access.require(ws, WorkspaceRole.ADMIN);
        Object config = body.settings().settings();
        String password = body.settings().password();
        Datasource saved = null;
        if ((password == null || password.isEmpty()) && body.existingId() != null) {
            saved = find(granted.workspace().id(), body.existingId());
            password = workspaceSecrets.resolve(granted.workspace().id(), saved.secretRef());
        } else if (password != null && (password.startsWith("env:") || WorkspaceSecrets.isReference(password)
                || io.opendbapi.core.secret.VaultSecrets.isReference(password))) {
            password = workspaceSecrets.resolve(granted.workspace().id(), password);
        }
        DatasourceTestResult result = run(config, password);
        if (saved != null && config.equals(saved.isHttp() ? saved.http() : saved.config())) {
            // The settings on screen are the saved ones: this was a test of the saved datasource.
            UUID id = saved.id();
            scope.run(granted.workspace().id(), () -> store.recordTest(id, result.ok(), result.message()));
        }
        return result;
    }

    @PostMapping("/{id}:test")
    @Operation(operationId = "testDatasource", summary = "Check a saved datasource can connect (author)")
    public DatasourceTestResult test(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        Datasource ds = find(granted.workspace().id(), id);
        DatasourceTestResult result = run(ds.isHttp() ? ds.http() : ds.config(),
                workspaceSecrets.resolve(granted.workspace().id(), ds.secretRef()));
        scope.run(granted.workspace().id(), () -> store.recordTest(id, result.ok(), result.message()));
        return result;
    }

    @GetMapping("/{id}/schemas")
    @Operation(operationId = "listSchemas", summary = "Schemas of the database, system ones left out (author)")
    public List<String> schemas(@PathVariable String ws, @PathVariable UUID id) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        return registry.schemas(find(granted.workspace().id(), id));
    }

    @GetMapping("/{id}/tables")
    @Operation(operationId = "listTables", summary = "Tables and views of a schema, at most 2000 (author)")
    public DataSourceRegistry.Tables tables(@PathVariable String ws, @PathVariable UUID id,
                                            @RequestParam(required = false) String schema) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        return registry.tables(find(granted.workspace().id(), id), schema);
    }

    @GetMapping("/{id}/columns")
    @Operation(operationId = "listColumns", summary = "Columns of one table (author)")
    public List<DataSourceRegistry.TableColumn> columns(@PathVariable String ws, @PathVariable UUID id,
                                                   @RequestParam(required = false) String schema,
                                                   @RequestParam String table) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        return registry.columns(find(granted.workspace().id(), id), schema, table);
    }

    private Datasource find(UUID workspaceId, UUID id) {
        return scope.call(workspaceId, () -> store.find(id))
                .orElseThrow(() -> ApiProblem.notFound("datasource_not_found", "That datasource does not exist in this workspace."));
    }

    private DatasourceTestResult run(Object config, String password) {
        try {
            if (config instanceof HttpConfig http) {
                var answer = httpSources.test(http, password);
                return new DatasourceTestResult(true, "Connected: the API answered %s in %d ms.".formatted(answer.product(), answer.latencyMs()),
                        answer.product(), answer.version(), answer.latencyMs());
            }
            var result = registry.test((JdbcConfig) config, password);
            return new DatasourceTestResult(true, "Connected to %s %s in %d ms.".formatted(result.product(), result.version(), result.latencyMs()),
                    result.product(), result.version(), result.latencyMs());
        } catch (ApiProblem e) {
            if ("connection_failed".equals(e.code()) || "host_not_allowed".equals(e.code())) {
                return new DatasourceTestResult(false, e.getMessage(), null, null, null);
            }
            throw e;
        }
    }
}
