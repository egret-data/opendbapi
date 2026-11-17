package io.opendbapi.core.endpoint;

import io.opendbapi.core.datapolicy.DataPolicies;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs another endpoint for a workflow's api node (spec §6.1): its live version, with its own
 * parameter checks, rules, datasource and data policies, as the same caller. Inside a workflow
 * that runs as one transaction, a callee on the same datasource joins it; otherwise it runs on
 * its own connection. Calls nest at most
 * {@link #MAX_DEPTH} deep, so endpoints calling each other stop.
 */
@Component
public class EndpointInvoker {

    static final int MAX_DEPTH = 3;

    private final EndpointRegistry registry;
    private final WorkspaceScope scope;
    private final DatasourceStore datasources;
    private final DataPolicies policies;
    private final QueryExecutor executor;

    public EndpointInvoker(EndpointRegistry registry, WorkspaceScope scope, DatasourceStore datasources, DataPolicies policies,
                           QueryExecutor executor) {
        this.registry = registry;
        this.scope = scope;
        this.datasources = datasources;
        this.policies = policies;
        this.executor = executor;
    }

    /**
     * @param serviceAccountId the caller, whose data policies apply; null for previews by authors
     */
    public QueryExecutor.EndpointCaller caller(UUID workspaceId, UUID serviceAccountId, QueryExecutor.RunMode mode) {
        return caller(workspaceId, serviceAccountId, mode, 1);
    }

    private QueryExecutor.EndpointCaller caller(UUID workspaceId, UUID serviceAccountId, QueryExecutor.RunMode mode, int depth) {
        return (endpointId, values, shared) -> {
            if (depth > MAX_DEPTH) {
                throw new ApiProblem(500, "workflow_failed", "Endpoints call each other more than %d deep.".formatted(MAX_DEPTH));
            }
            EndpointRegistry.Route route = registry.find(workspaceId, endpointId)
                    .orElseThrow(() -> new ApiProblem(503, "endpoint_unavailable", "The called endpoint is not live."));
            EndpointDefinition def = route.definition();
            Map<String, Object> params = ParamBinder.bind(def.params(), ParamBinder.fromValues(def.params(), values),
                    def.pagination().enabled() ? EndpointValidator.RESERVED : java.util.Set.of("format"));
            ParamRules.enforce(def.rules(), params);
            Datasource ds = scope.call(workspaceId, () -> datasources.find(route.task().datasourceId()))
                    .orElseThrow(() -> new ApiProblem(503, "datasource_missing", "The called endpoint's datasource is not available."));
            DataPolicies.Plan plan = policies.plan(workspaceId, endpointId, ds.id(), serviceAccountId);
            QueryExecutor.PageRequest page = def.pagination().enabled()
                    ? new QueryExecutor.PageRequest(1, def.pagination().defaultSize(), false) : null;
            // Spec §6.1: inside a workflow transaction, a callee on the same datasource joins it.
            java.sql.Connection join = shared != null && shared.datasourceId().equals(ds.id()) ? shared.connection() : null;
            QueryExecutor.QueryResult result = executor.run(ds, route.task(), params, page, def.limits(), mode, plan,
                    caller(workspaceId, serviceAccountId, mode, depth + 1), join);
            return ResponseBody.build(def, result, page,
                    r -> DataPolicies.Plan.apply(plan.masksFor(r.columns()), r.columns(), r.rows())).data();
        };
    }
}
