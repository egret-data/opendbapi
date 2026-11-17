package io.opendbapi.core.endpoint.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.datapolicy.DataPolicies;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.endpoint.CompiledTask;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.EndpointValidator;
import io.opendbapi.core.endpoint.EndpointVersion;
import io.opendbapi.core.endpoint.ParamBinder;
import io.opendbapi.core.endpoint.ParamRules;
import io.opendbapi.core.endpoint.QueryExecutor;
import io.opendbapi.core.endpoint.test.EndpointTestCase.JsonPathCheck;
import io.opendbapi.core.endpoint.test.EndpointTestCase.TestCaseResult;
import io.opendbapi.core.endpoint.test.EndpointTestCase.TestExpectation;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.sql.template.TemplateException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Runs an endpoint's test cases against its saved draft (spec §11.3). Each run goes through
 * binding, rules, and, for a test that calls as a service account, the grant check and data
 * policies; the query always rolls back.
 */
@Service
public class EndpointTests {

    /** Outcome of running some or all of an endpoint's tests. */
    public record TestRunSummary(int passed, int failed, List<TestCaseResult> results) {
        public boolean ok() {
            return failed == 0;
        }
    }

    /** What the draft answered, before assertions. */
    private record Answer(int status, String code, JsonNode body, List<QueryExecutor.ResultColumn> columns, Integer rowCount,
                          long durationMs) {
    }

    private static final Configuration JSON_PATH = Configuration.builder().jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider()).build();

    private final WorkspaceScope scope;
    private final EndpointStore endpoints;
    private final EndpointTestStore store;
    private final DatasourceStore datasources;
    private final QueryExecutor executor;
    private final DataPolicies policies;
    private final ServiceAccountStore accounts;
    private final ObjectMapper json;

    public EndpointTests(WorkspaceScope scope, EndpointStore endpoints, EndpointTestStore store, DatasourceStore datasources,
                         QueryExecutor executor, DataPolicies policies, ServiceAccountStore accounts, ObjectMapper json) {
        this.scope = scope;
        this.endpoints = endpoints;
        this.store = store;
        this.datasources = datasources;
        this.executor = executor;
        this.policies = policies;
        this.accounts = accounts;
        this.json = json;
    }

    /** @param testId one test, or null for all of them */
    public TestRunSummary run(UUID workspaceId, UUID endpointId, UUID testId) {
        return scope.call(workspaceId, () -> {
            Endpoint e = endpoints.find(endpointId).orElseThrow(() -> ApiProblem.notFound("endpoint_not_found", "That endpoint does not exist."));
            List<EndpointTestCase> cases = testId == null ? store.list(endpointId)
                    : List.of(store.find(endpointId, testId).orElseThrow(EndpointTests::testNotFound));
            JsonNode liveSchema = e.liveVersion() == null ? null
                    : endpoints.version(endpointId, e.liveVersion()).map(EndpointVersion::outputSchema).orElse(null);
            List<TestCaseResult> results = new ArrayList<>();
            for (EndpointTestCase t : cases) {
                TestCaseResult r = check(t, answer(workspaceId, e, t), liveSchema);
                store.recordResult(t.id(), r);
                results.add(r);
            }
            int passed = (int) results.stream().filter(TestCaseResult::passed).count();
            return new TestRunSummary(passed, results.size() - passed, results);
        });
    }

    /** Whether the endpoint has tests at all; called inside the workspace scope. */
    public boolean hasTests(UUID endpointId) {
        return !store.list(endpointId).isEmpty();
    }

    private io.opendbapi.core.endpoint.EndpointInvoker invoker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInvoker(io.opendbapi.core.endpoint.EndpointInvoker invoker) {
        this.invoker = invoker;
    }

    private Answer answer(UUID workspaceId, Endpoint e, EndpointTestCase t) {
        long start = System.nanoTime();
        EndpointDefinition def = e.definition();
        try {
            UUID sa = t.serviceAccountId();
            if (sa != null && !"public".equals(e.access())) {
                switch (accounts.serviceAccountStatus(sa, e.id())) {
                    case INVALID -> throw new ApiProblem(401, "invalid_token", "The service account is disabled.");
                    case NOT_GRANTED -> throw new ApiProblem(403, "not_granted", "The service account has no grant for this endpoint.");
                    case GRANTED -> { }
                }
            }
            Datasource ds = def.task().datasourceId() == null ? null : datasources.find(def.task().datasourceId()).orElse(null);
            if (ds == null) {
                throw new ApiProblem(503, "datasource_missing", "The draft's datasource does not exist.");
            }
            CompiledTask task;
            try {
                task = CompiledTask.compile(def.task()).forMethod(e.method());
            } catch (TemplateException ex) {
                throw ApiProblem.badRequest("template", ex.getMessage());
            }
            Map<String, Object> raw = new HashMap<>(t.params());
            Map<String, Object> params = ParamBinder.bind(def.params(), ParamBinder.fromValues(def.params(), raw),
                    EndpointValidator.RESERVED);
            ParamRules.enforce(def.rules(), params);
            QueryExecutor.PageRequest page = null;
            if (def.pagination().keyset()) {
                int size = intValue(raw.get("size"), def.pagination().defaultSize());
                Object after = raw.get("after");
                page = new QueryExecutor.PageRequest(1, size, false, def.pagination().keys(),
                        after == null || after.toString().isBlank() ? null
                                : io.opendbapi.core.endpoint.KeysetCursor.decode(after.toString(), def.pagination().keys().size()));
            } else if (def.pagination().enabled()) {
                int p = intValue(raw.get("page"), 1);
                int size = intValue(raw.get("size"), def.pagination().defaultSize());
                if (p < 1 || size < 1 || size > def.pagination().maxSize()) {
                    throw ApiProblem.badRequest("invalid_param", "page or size is out of range.");
                }
                page = new QueryExecutor.PageRequest(p, size, def.pagination().count());
            }
            DataPolicies.Plan plan = sa == null ? DataPolicies.Plan.NONE : policies.plan(workspaceId, e.id(), ds.id(), sa);
            QueryExecutor.QueryResult result = executor.run(ds, task, params, page, def.limits(), QueryExecutor.RunMode.PREVIEW,
                    plan, invoker == null ? null : invoker.caller(workspaceId, sa, QueryExecutor.RunMode.PREVIEW));
            io.opendbapi.core.endpoint.ResponseBody.Parts parts = io.opendbapi.core.endpoint.ResponseBody.build(def, result, page,
                    r -> DataPolicies.Plan.apply(plan.masksFor(r.columns()), r.columns(), r.rows()));
            ObjectNode body = json.createObjectNode();
            body.set("data", json.valueToTree(parts.data()));
            body.set("meta", json.valueToTree(parts.pageMeta()));
            Integer rowCount = result.value() instanceof QueryExecutor.ScriptValue v
                    ? (v.data() instanceof java.util.Collection<?> c ? c.size() : null)
                    : result.affectedRows() != null ? null : result.rows().size();
            return new Answer(200, null, body, result.columns(), rowCount, elapsed(start));
        } catch (ApiProblem p) {
            return new Answer(p.status(), p.code(), problem(p.code(), p.getMessage()), List.of(), null, elapsed(start));
        } catch (QueryExecutor.QueryFailedException q) {
            return new Answer(500, "query_failed", problem("query_failed", q.getMessage()), List.of(), null, elapsed(start));
        }
    }

    private TestCaseResult check(EndpointTestCase t, Answer a, JsonNode liveSchema) {
        TestExpectation x = t.expect() == null ? new TestExpectation(null, null, null, null, null) : t.expect();
        List<String> failures = new ArrayList<>();
        int wantStatus = x.status() == null ? 200 : x.status();
        if (a.status() != wantStatus) {
            String why = a.code() == null ? "" : " (%s: %s)".formatted(a.code(), a.body().path("detail").asText());
            failures.add("Expected status %d, got %d%s.".formatted(wantStatus, a.status(), why));
        }
        if (x.rowCount() != null) {
            if (a.rowCount() == null) {
                failures.add("Expected rows, but the answer has none.");
            } else {
                if (x.rowCount().min() != null && a.rowCount() < x.rowCount().min()) {
                    failures.add("Expected at least %d rows, got %d.".formatted(x.rowCount().min(), a.rowCount()));
                }
                if (x.rowCount().max() != null && a.rowCount() > x.rowCount().max()) {
                    failures.add("Expected at most %d rows, got %d.".formatted(x.rowCount().max(), a.rowCount()));
                }
            }
        }
        for (JsonPathCheck c : x.jsonPath()) {
            failures.addAll(checkPath(a.body(), c));
        }
        if (Boolean.TRUE.equals(x.schemaMatchesLive())) {
            if (liveSchema == null) {
                failures.add("There is no live version to compare the result columns with.");
            } else if (a.status() == 200) {
                // Names only: the database may report a wider type once parameters are bound (int4 vs int8).
                List<String> live = new ArrayList<>();
                liveSchema.forEach(c -> live.add(c.path("name").asText()));
                List<String> now = a.columns().stream().map(QueryExecutor.ResultColumn::name).toList();
                if (!live.equals(now)) {
                    failures.add("The result columns changed: live has %s, the draft returns %s.".formatted(live, now));
                }
            }
        }
        if (x.maxDurationMs() != null && a.durationMs() > x.maxDurationMs()) {
            failures.add("Took %d ms, more than %d ms.".formatted(a.durationMs(), x.maxDurationMs()));
        }
        return new TestCaseResult(t.id(), t.name(), failures.isEmpty(), a.status(), a.code(), a.durationMs(), a.rowCount(),
                failures, Instant.now());
    }

    private static List<String> checkPath(JsonNode body, JsonPathCheck c) {
        if (c.path() == null || !c.path().startsWith("$")) {
            return List.of("'%s' is not a JSONPath; start it with $.".formatted(c.path()));
        }
        // "Present" means matched and not null: json-path reports an index past the end of an
        // array as a null match, so null and missing cannot be told apart reliably.
        JsonNode found;
        try {
            found = JsonPath.using(JSON_PATH).parse(body).read(c.path());
        } catch (com.jayway.jsonpath.JsonPathException | IllegalArgumentException e) {
            found = null;
        }
        boolean present = found != null && !found.isNull() && !found.isMissingNode();
        List<String> out = new ArrayList<>();
        if (c.exists() != null && c.exists() != present) {
            out.add(c.exists() ? "%s is missing.".formatted(c.path()) : "%s should not be there.".formatted(c.path()));
        }
        if (c.equals() != null && !c.equals().isNull() && (!present || !same(found, c.equals()))) {
            out.add("%s is %s, expected %s.".formatted(c.path(), present ? found.toString() : "missing", c.equals()));
        }
        return out;
    }

    /** JSON equality where numbers compare by value (1 equals 1.0). */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isTextual() && b.isNumber()) {
            try {
                return new BigDecimal(a.asText()).compareTo(b.decimalValue()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return a.equals(b);
    }

    private ObjectNode problem(String code, String detail) {
        return json.createObjectNode().put("code", code).put("detail", detail);
    }

    private static int intValue(Object v, int fallback) {
        if (v == null || String.valueOf(v).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            throw ApiProblem.badRequest("invalid_param", "page and size must be whole numbers.");
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static ApiProblem testNotFound() {
        return ApiProblem.notFound("test_not_found", "That test case does not exist.");
    }

    /** Checks a test case before it is saved. */
    public static void validate(EndpointTestCase t) {
        if (t.name() == null || t.name().isBlank() || t.name().length() > 120) {
            throw ApiProblem.badRequest("invalid_test", "Give the test a name of at most 120 characters.");
        }
        if (t.params() != null && t.params().size() > 100) {
            throw ApiProblem.badRequest("invalid_test", "At most 100 parameter values.");
        }
        TestExpectation x = t.expect();
        if (x != null) {
            if (x.status() != null && (x.status() < 100 || x.status() > 599)) {
                throw ApiProblem.badRequest("invalid_test", "The expected status must be an HTTP status code.");
            }
            if (x.jsonPath().size() > 50) {
                throw ApiProblem.badRequest("invalid_test", "At most 50 JSONPath checks.");
            }
            for (JsonPathCheck c : x.jsonPath()) {
                if (c.path() == null || !c.path().startsWith("$") || c.path().length() > 300) {
                    throw ApiProblem.badRequest("invalid_test", "'%s' is not a JSONPath; start it with $.".formatted(c.path()));
                }
                try {
                    JsonPath.compile(c.path());
                } catch (RuntimeException e) {
                    throw ApiProblem.badRequest("invalid_test", "'%s' is not a valid JSONPath.".formatted(c.path()));
                }
            }
        }
    }
}
