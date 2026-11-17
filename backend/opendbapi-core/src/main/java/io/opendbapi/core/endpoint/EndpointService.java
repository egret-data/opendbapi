package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.sql.template.TemplateException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The endpoint lifecycle (spec §5.1): draft → validate → publish (immutable version) → live;
 * disable/enable; trash only when not live, restore to disabled; rollback points live at an
 * older version without touching the draft. Every call runs in the workspace's scope.
 */
@Service
public class EndpointService {

    public record Draft(UUID groupId, String name, String description, String method, String path, String access,
                        EndpointDefinition definition) {
    }

    public record Preview(QueryExecutor.QueryResult result, List<EndpointValidator.Finding> findings) {
    }

    private final WorkspaceScope scope;
    private final EndpointStore store;
    private final DatasourceStore datasources;
    private final EndpointValidator validator;
    private final EndpointRegistry registry;
    private final QueryExecutor executor;
    private final ObjectMapper json;
    private final io.opendbapi.core.datapolicy.DataPolicyStore dataPolicies;
    private io.opendbapi.core.endpoint.test.EndpointTests tests;
    private boolean requireTestsPass;

    @org.springframework.beans.factory.annotation.Autowired
    void setTests(io.opendbapi.core.endpoint.test.EndpointTests tests,
                  @org.springframework.beans.factory.annotation.Value("${opendbapi.publish.require-tests-pass:false}") boolean requireTestsPass) {
        this.tests = tests;
        this.requireTestsPass = requireTestsPass;
    }

    public EndpointService(WorkspaceScope scope, EndpointStore store, DatasourceStore datasources, EndpointValidator validator,
                           EndpointRegistry registry, QueryExecutor executor, ObjectMapper json,
                           io.opendbapi.core.datapolicy.DataPolicyStore dataPolicies) {
        this.dataPolicies = dataPolicies;
        this.scope = scope;
        this.store = store;
        this.datasources = datasources;
        this.validator = validator;
        this.registry = registry;
        this.executor = executor;
        this.json = json;
    }

    private EndpointInvoker invoker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInvoker(EndpointInvoker invoker) {
        this.invoker = invoker;
    }

    public Endpoint create(UUID ws, Draft d, UUID userId) {
        return scope.call(ws, () -> store.create(ws, d.groupId(), d.name(), d.description(), d.method(), d.path(),
                d.access(), d.definition(), userId));
    }

    public Endpoint get(UUID ws, UUID id) {
        return scope.call(ws, () -> find(id));
    }

    /** Route changes are refused while live: published URLs are contracts with callers. */
    public Endpoint update(UUID ws, UUID id, Draft d) {
        return scope.call(ws, () -> {
            Endpoint e = find(id);
            requireNotTrashed(e);
            boolean routeChanged = !e.method().equals(d.method()) || !e.path().equals(d.path());
            if (routeChanged && Endpoint.LIVE.equals(e.state())) {
                throw ApiProblem.conflict("route_locked",
                        "A live endpoint's method and path cannot change: callers depend on them. Disable it first, "
                                + "or create a new endpoint for the new route.");
            }
            return store.updateDraft(id, d.groupId(), d.name(), d.description(), d.method(), d.path(), d.access(), d.definition());
        });
    }

    public EndpointValidator.Validation validate(UUID ws, UUID id) {
        return scope.call(ws, () -> {
            Endpoint e = find(id);
            return withWorkflowTargets(e, withBreakingChanges(e, validator.validate(e.method(), e.path(), e.definition(),
                    datasourceOf(e.definition()), policies(e))));
        });
    }

    /** Spec §6.1: api nodes must call endpoints of this workspace; parameters must exist there. */
    private EndpointValidator.Validation withWorkflowTargets(Endpoint e, EndpointValidator.Validation v) {
        EndpointDefinition.Task task = e.definition().task();
        if (!task.workflow()) {
            return v;
        }
        List<EndpointValidator.Finding> findings = new java.util.ArrayList<>(v.findings());
        for (EndpointDefinition.WorkflowNode n : task.nodes()) {
            if (!"api".equals(n.kind()) || n.endpointId() == null) {
                continue;
            }
            var target = store.find(n.endpointId()).filter(t -> !t.trashed());
            if (target.isEmpty()) {
                findings.add(new EndpointValidator.Finding("error", "workflow", "Node '%s' calls an endpoint that does not exist.".formatted(n.id())));
                continue;
            }
            if (target.get().id().equals(e.id())) {
                findings.add(new EndpointValidator.Finding("error", "workflow", "Node '%s' calls this endpoint itself.".formatted(n.id())));
            }
            if (!Endpoint.LIVE.equals(target.get().state())) {
                findings.add(new EndpointValidator.Finding("warning", "workflow",
                        "Node '%s' calls '%s', which is not live; the call fails until it is published.".formatted(n.id(), target.get().name())));
            }
            java.util.Set<String> known = new java.util.HashSet<>();
            target.get().definition().params().forEach(p -> known.add(p.name()));
            for (String name : n.params().keySet()) {
                if (!known.contains(name)) {
                    findings.add(new EndpointValidator.Finding("warning", "workflow",
                            "Node '%s' passes '%s', which '%s' does not declare.".formatted(n.id(), name, target.get().name())));
                }
            }
        }
        return new EndpointValidator.Validation(findings, v.detectedParameters(), v.outputColumns());
    }

    /**
     * Spec §16: compares the draft's result columns with the live version's. Dropped columns
     * or changed types break callers; the warning suggests a new major instead.
     */
    private EndpointValidator.Validation withBreakingChanges(Endpoint e, EndpointValidator.Validation v) {
        if (e.liveVersion() == null || v.outputColumns() == null || v.outputColumns().isEmpty()) {
            return v;
        }
        var live = store.version(e.id(), e.liveVersion()).map(EndpointVersion::outputSchema).orElse(null);
        if (live == null || !live.isArray() || live.isEmpty()) {
            return v;
        }
        Map<String, String> now = new java.util.HashMap<>();
        v.outputColumns().forEach(c -> now.put(c.name(), c.type()));
        List<String> problems = new java.util.ArrayList<>();
        for (var col : live) {
            String name = col.path("name").asText();
            String type = col.path("type").asText(null);
            if (!now.containsKey(name)) {
                problems.add("drops column '" + name + "'");
            } else if (type != null && now.get(name) != null && !type.equalsIgnoreCase(now.get(name))) {
                problems.add("changes '" + name + "' from " + type + " to " + now.get(name));
            }
        }
        if (problems.isEmpty()) {
            return v;
        }
        List<EndpointValidator.Finding> findings = new java.util.ArrayList<>(v.findings());
        findings.add(new EndpointValidator.Finding("warning", "breaking_change", "Compared with live version %d, this %s; callers may break. Consider a new major version instead."
                .formatted(e.liveVersion(), String.join(", ", problems))));
        return new EndpointValidator.Validation(findings, v.detectedParameters(), v.outputColumns());
    }

    /** Policies on the endpoint and on the datasource its draft uses; called inside the workspace scope. */
    private EndpointValidator.PolicyContext policies(Endpoint e) {
        UUID datasourceId = e.definition().task().datasourceId();
        String rowFilter = null;
        java.util.List<String> masks = new java.util.ArrayList<>();
        for (io.opendbapi.core.datapolicy.DataPolicyStore.Stored stored : dataPolicies.all()) {
            io.opendbapi.core.datapolicy.DataPolicy p = stored.policy();
            boolean mine = e.id().equals(p.endpointId()) || (datasourceId != null && datasourceId.equals(p.datasourceId()));
            if (!mine) {
                continue;
            }
            if (io.opendbapi.core.datapolicy.DataPolicy.ROW_FILTER.equals(p.kind())) {
                rowFilter = p.column();
            } else {
                masks.add(p.column());
            }
        }
        return new EndpointValidator.PolicyContext(e.access(), rowFilter, masks);
    }

    public EndpointVersion publish(UUID ws, UUID id, String note, UUID userId) {
        if (requireTestsPass && tests != null) {
            // Spec §11.3: every test case of the draft must pass first.
            var summary = tests.run(ws, id, null);
            if (!summary.ok()) {
                throw ApiProblem.conflict("tests_failed", "Publishing needs every test to pass; failing: " + summary.results().stream()
                        .filter(r -> !r.passed()).map(r -> "'" + r.name() + "'").reduce((a, b) -> a + ", " + b).orElse("") + ".");
            }
        }
        EndpointVersion v = scope.call(ws, () -> {
            Endpoint e = find(id);
            requireNotTrashed(e);
            EndpointValidator.Validation validation = withWorkflowTargets(e, validator.validate(e.method(), e.path(), e.definition(),
                    datasourceOf(e.definition()), policies(e)));
            if (!validation.ok()) {
                throw new ApiProblem(409, "validation_failed", "Fix the errors before publishing: " + validation.findings().stream()
                        .filter(f -> "error".equals(f.severity())).map(EndpointValidator.Finding::message)
                        .reduce((a, b) -> a + " " + b).orElse(""));
            }
            if (!e.dirty() && Endpoint.LIVE.equals(e.state())) {
                throw ApiProblem.conflict("nothing_to_publish", "The live version already matches the draft.");
            }
            return store.publish(e, json.valueToTree(validation.outputColumns()), note, userId);
        });
        registry.invalidate(ws);
        return v;
    }

    public Endpoint disable(UUID ws, UUID id) {
        return transition(ws, id, e -> {
            if (!Endpoint.LIVE.equals(e.state())) {
                throw ApiProblem.conflict("not_live", "Only a live endpoint can be disabled.");
            }
            store.setState(id, Endpoint.DISABLED);
        });
    }

    public Endpoint enable(UUID ws, UUID id) {
        return transition(ws, id, e -> {
            requireNotTrashed(e);
            if (!Endpoint.DISABLED.equals(e.state()) || e.liveVersionId() == null) {
                throw ApiProblem.conflict("not_disabled", "Only a disabled endpoint with a published version can be enabled.");
            }
            store.setState(id, Endpoint.LIVE);
        });
    }

    /** Trash only draft or disabled endpoints: disabling is the one way to stop serving (spec §5.1). */
    public Endpoint trash(UUID ws, UUID id) {
        return transition(ws, id, e -> {
            if (Endpoint.LIVE.equals(e.state())) {
                throw ApiProblem.conflict("endpoint_live", "A live endpoint cannot go to the trash. Disable it first.");
            }
            store.setTrashed(id, true);
        });
    }

    /** Back from the trash as disabled (or draft if never published), never straight to live. */
    public Endpoint restore(UUID ws, UUID id) {
        return transition(ws, id, e -> {
            if (!e.trashed()) {
                throw ApiProblem.conflict("not_trashed", "The endpoint is not in the trash.");
            }
            store.setTrashed(id, false);
            if (e.liveVersionId() != null) {
                store.setState(id, Endpoint.DISABLED);
            }
        });
    }

    public void delete(UUID ws, UUID id) {
        scope.run(ws, () -> {
            Endpoint e = find(id);
            if (!e.trashed()) {
                throw ApiProblem.conflict("not_trashed", "Move the endpoint to the trash before deleting it for good.");
            }
            store.delete(id);
        });
        registry.invalidate(ws);
    }

    public Endpoint duplicate(UUID ws, UUID id, UUID userId) {
        return scope.call(ws, () -> {
            Endpoint e = find(id);
            // Check first: a failed INSERT would abort the surrounding PostgreSQL transaction.
            for (int n = 1; n < 100; n++) {
                String path = e.path() + (n == 1 ? "-copy" : "-copy-" + n);
                if (!store.routeTaken(e.method(), path)) {
                    return store.create(ws, e.groupId(), e.name() + " (copy)", e.description(), e.method(),
                            path, e.access(), e.definition(), userId);
                }
            }
            throw ApiProblem.conflict("route_taken", "No free path for the copy; rename existing copies first.");
        });
    }

    private static final java.util.regex.Pattern VERSION_PREFIX = java.util.regex.Pattern.compile("^v(\\d+)/(.+)$");
    private static final java.util.regex.Pattern NAME_SUFFIX = java.util.regex.Pattern.compile("\\s+v\\d+$");

    /**
     * Spec §16: copies the draft to a new route as the next major of its lineage, as a draft.
     * Without a path, {@code v1/orders} becomes {@code v2/orders} and {@code orders} becomes
     * {@code v2/orders}.
     */
    public Endpoint newMajor(UUID ws, UUID id, String path, UUID userId) {
        return scope.call(ws, () -> {
            Endpoint e = find(id);
            requireNotTrashed(e);
            int major = store.highestMajor(e.lineageId()) + 1;
            String target = path == null || path.isBlank() ? nextMajorPath(e.path(), major) : path.strip().replaceFirst("^/", "");
            if (target.equals(e.path())) {
                throw ApiProblem.badRequest("invalid_path", "A new major needs a different path.");
            }
            String name = NAME_SUFFIX.matcher(e.name()).replaceFirst("") + " v" + major;
            return store.createMajor(e, name.length() > 120 ? name.substring(0, 120) : name, target, major, userId);
        });
    }

    static String nextMajorPath(String path, int major) {
        var m = VERSION_PREFIX.matcher(path);
        return m.matches() ? "v" + major + "/" + m.group(2) : "v" + major + "/" + path;
    }

    /** Announces in every response that callers should move on (spec §16); sunset is optional. */
    public Endpoint deprecate(UUID ws, UUID id, java.time.OffsetDateTime sunset) {
        return transition(ws, id, e -> {
            requireNotTrashed(e);
            store.setDeprecation(id, e.deprecatedAt() != null ? e.deprecatedAt() : java.time.OffsetDateTime.now(), sunset);
        });
    }

    public Endpoint undeprecate(UUID ws, UUID id) {
        return transition(ws, id, e -> store.setDeprecation(id, null, null));
    }

    public List<EndpointStore.LineageMember> lineage(UUID ws, UUID id) {
        return scope.call(ws, () -> store.lineage(find(id).lineageId()));
    }

    public List<EndpointVersion> versions(UUID ws, UUID id) {
        return scope.call(ws, () -> {
            find(id);
            return store.versions(id);
        });
    }

    public EndpointVersion version(UUID ws, UUID id, int version) {
        return scope.call(ws, () -> store.version(id, version)
                .orElseThrow(() -> ApiProblem.notFound("version_not_found", "Version %d does not exist.".formatted(version))));
    }

    /** Serve an older version; a disabled endpoint stays disabled. The draft is untouched. */
    public Endpoint rollback(UUID ws, UUID id, int version) {
        return transition(ws, id, e -> {
            requireNotTrashed(e);
            EndpointVersion target = store.version(id, version)
                    .orElseThrow(() -> ApiProblem.notFound("version_not_found", "Version %d does not exist.".formatted(version)));
            if (!target.snapshot().method().equals(e.method()) || !target.snapshot().path().equals(e.path())) {
                // Routes of old versions may now belong to someone else; the draft route is the reserved one.
                throw ApiProblem.conflict("route_changed",
                        "Version %d answered a different route; it cannot be served at this endpoint's current route.".formatted(version));
            }
            store.setLive(id, target.id(), Endpoint.DISABLED.equals(e.state()) ? Endpoint.DISABLED : Endpoint.LIVE);
        });
    }

    /** Runs the on-screen draft with test values; never commits (spec §11.1). */
    public Preview preview(UUID ws, String method, String path, EndpointDefinition definition, Map<String, Object> rawParams,
                           Integer page, Integer size) {
        return scope.call(ws, () -> {
            Datasource ds = datasourceOf(definition);
            EndpointValidator.Validation validation = validator.validate(method, path, definition, ds);
            if (ds == null) {
                throw ApiProblem.badRequest("no_datasource", "Choose an existing datasource first.");
            }
            CompiledTask task;
            try {
                task = CompiledTask.compile(definition.task()).forMethod(method);
            } catch (TemplateException e) {
                throw ApiProblem.badRequest("template", e.getMessage());
            }
            Map<String, Object> params = ParamBinder.bind(definition.params(), ParamBinder.fromValues(definition.params(), rawParams), EndpointValidator.RESERVED);
            ParamRules.enforce(definition.rules(), params);
            QueryExecutor.PageRequest pageRequest = definition.pagination().enabled()
                    ? new QueryExecutor.PageRequest(page == null ? 1 : page,
                            Math.min(size == null ? definition.pagination().defaultSize() : size, definition.pagination().maxSize()),
                            definition.pagination().count())
                    : null;
            QueryExecutor.QueryResult result = executor.run(ds, task, params, pageRequest,
                    new EndpointDefinition.Limits(Math.min(definition.limits().timeoutSeconds(), 10), 100), QueryExecutor.RunMode.PREVIEW,
                    QueryExecutor.Policy.NONE, invoker == null ? null : invoker.caller(ws, null, QueryExecutor.RunMode.PREVIEW));
            return new Preview(result, validation.findings());
        });
    }

    /** Preview values arrive as one JSON object; route each to where its parameter lives. */
    private Endpoint transition(UUID ws, UUID id, java.util.function.Consumer<Endpoint> change) {
        Endpoint result = scope.call(ws, () -> {
            change.accept(find(id));
            return store.find(id).orElseThrow();
        });
        registry.invalidate(ws);
        return result;
    }

    private Endpoint find(UUID id) {
        return store.find(id).orElseThrow(() -> ApiProblem.notFound("endpoint_not_found", "That endpoint does not exist in this workspace."));
    }

    private Datasource datasourceOf(EndpointDefinition def) {
        return def.task().datasourceId() == null ? null : datasources.find(def.task().datasourceId()).orElse(null);
    }

    private static void requireNotTrashed(Endpoint e) {
        if (e.trashed()) {
            throw ApiProblem.conflict("endpoint_trashed", "The endpoint is in the trash. Restore it first.");
        }
    }
}
