package io.opendbapi.core.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.opendbapi.core.client.CallerAuthorizer;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.datapolicy.DataPolicies;
import io.opendbapi.core.datapolicy.DataPolicy;
import io.opendbapi.core.datapolicy.DataPolicyStore;
import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.datasource.JdbcConfig;
import io.opendbapi.core.endpoint.ApiGroupStore;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointRegistry;
import io.opendbapi.core.endpoint.EndpointService;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.test.EndpointTestCase;
import io.opendbapi.core.endpoint.test.EndpointTestStore;
import io.opendbapi.core.endpoint.test.EndpointTests;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.WorkspaceSecrets;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Configuration as code (spec §15): compares a YAML file in the export format with the
 * workspace ({@code plan}) and makes the workspace match it ({@code apply}). Documents are
 * matched by name (an endpoint by its route); an environment maps the logical datasource
 * names of the file to this workspace's datasources. Nothing is deleted unless {@code prune}
 * asks for it, and endpoints are published only with {@code publish}, after their tests pass.
 */
@Service
public class ConfigPlanner {

    public static final String CREATE = "create";
    public static final String UPDATE = "update";
    public static final String DELETE = "delete";
    public static final String UNCHANGED = "unchanged";
    public static final String SKIP = "skip";

    /** @param environment null applies the file as written */
    public record PlanOptions(String environment, boolean prune, boolean publish) {
    }

    public record ConfigChange(String kind, String name,
                               @Schema(allowableValues = {"create", "update", "delete", "unchanged", "skip"}) String action,
                               @Schema(description = "What differs, e.g. spec.task or publish") List<String> details) {
    }

    public record ConfigPlan(@Schema(nullable = true) String environment, boolean prune, boolean publish, List<ConfigChange> changes,
                             @Schema(description = "Number of changes per action") Map<String, Integer> counts,
                             @Schema(description = "Identifies this plan; pass it to apply to refuse a workspace that changed since")
                             String digest) {

        public boolean hasChanges() {
            return changes.stream().anyMatch(c -> !UNCHANGED.equals(c.action()) && !SKIP.equals(c.action()));
        }
    }

    public record PublishOutcome(String endpoint,
                                 @Schema(allowableValues = {"published", "disabled", "tests_failed", "failed"}) String result,
                                 @Schema(nullable = true) String message) {
    }

    public record ConfigApplyResult(ConfigPlan plan, List<PublishOutcome> published) {

        public boolean ok() {
            return published.stream().allMatch(p -> "published".equals(p.result()) || "disabled".equals(p.result()));
        }
    }

    /** The parsed file and the current workspace, ready to diff; built inside the workspace scope. */
    private record Analysis(Map<String, ConfigDocument> desired, Map<String, ConfigDocument> current, Set<String> skipped,
                            Map<String, String> bindings, Map<String, Endpoint> endpoints) {
    }

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final WorkspaceScope scope;
    private final YamlExporter exporter;
    private final EnvironmentStore environments;
    private final DatasourceStore datasources;
    private final ApiGroupStore groups;
    private final EndpointStore endpointStore;
    private final EndpointService endpointService;
    private final EndpointTestStore testStore;
    private final ServiceAccountStore accounts;
    private final DataPolicyStore policyStore;
    private final ObjectMapper json;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    private WorkspaceSecrets workspaceSecrets;
    private DataSourceRegistry pools;
    private EndpointRegistry registry;
    private DataPolicies dataPolicies;
    private CallerAuthorizer callers;
    private EndpointTests tests;

    public ConfigPlanner(WorkspaceScope scope, YamlExporter exporter, EnvironmentStore environments, DatasourceStore datasources,
                         ApiGroupStore groups, EndpointStore endpointStore, EndpointService endpointService, EndpointTestStore testStore,
                         ServiceAccountStore accounts, DataPolicyStore policyStore, ObjectMapper json) {
        this.scope = scope;
        this.exporter = exporter;
        this.environments = environments;
        this.datasources = datasources;
        this.groups = groups;
        this.endpointStore = endpointStore;
        this.endpointService = endpointService;
        this.testStore = testStore;
        this.accounts = accounts;
        this.policyStore = policyStore;
        this.json = json;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setCollaborators(WorkspaceSecrets workspaceSecrets, DataSourceRegistry pools, EndpointRegistry registry, DataPolicies dataPolicies,
                          CallerAuthorizer callers, EndpointTests tests) {
        this.workspaceSecrets = workspaceSecrets;
        this.pools = pools;
        this.registry = registry;
        this.dataPolicies = dataPolicies;
        this.callers = callers;
        this.tests = tests;
    }

    public ConfigPlan plan(UUID ws, String file, PlanOptions options) {
        List<JsonNode> docs = parse(file);
        return scope.call(ws, () -> diff(analyse(docs, options), options));
    }

    /**
     * Writes every change in one transaction, then publishes or disables endpoints one by one
     * ({@code publish}), each only after its tests pass.
     *
     * @param expectedDigest when given, the plan must still have this digest
     */
    public ConfigApplyResult apply(UUID ws, String file, PlanOptions options, String expectedDigest, UUID userId) {
        List<JsonNode> docs = parse(file);
        List<UUID> evict = new ArrayList<>();
        List<String> toPublish = new ArrayList<>();
        List<String> toDisable = new ArrayList<>();
        ConfigPlan plan = scope.call(ws, () -> {
            Analysis a = analyse(docs, options);
            ConfigPlan p = diff(a, options);
            if (expectedDigest != null && !expectedDigest.isBlank() && !expectedDigest.equals(p.digest())) {
                throw ApiProblem.conflict("plan_changed", "The workspace or the file changed since the plan; plan again.");
            }
            write(ws, a, p, userId, evict);
            for (ConfigChange c : p.changes()) {
                if (ConfigDocument.ENDPOINT.equals(c.kind())) {
                    if (c.details().contains("publish")) {
                        toPublish.add(c.name());
                    } else if (c.details().contains("disable")) {
                        toDisable.add(c.name());
                    }
                }
            }
            return p;
        });
        registry.invalidate(ws);
        dataPolicies.invalidate(ws);
        callers.invalidate(ws);
        evict.forEach(pools::evict);

        List<PublishOutcome> outcomes = new ArrayList<>();
        Map<String, UUID> ids = scope.call(ws, () -> {
            Map<String, UUID> m = new HashMap<>();
            for (Endpoint e : endpointStore.list(new EndpointStore.Filter(null, null, null, null, false))) {
                m.put(route(e.method(), e.path()), e.id());
            }
            return m;
        });
        for (String routeKey : toPublish) {
            UUID id = ids.get(routeKey);
            try {
                boolean hasTests = !scope.call(ws, () -> testStore.list(id)).isEmpty();
                if (hasTests) {
                    var summary = tests.run(ws, id, null);
                    if (!summary.ok()) {
                        outcomes.add(new PublishOutcome(routeKey, "tests_failed", "Failing: " + String.join(", ", summary.results().stream()
                                .filter(r -> !r.passed()).map(r -> r.name() + " (" + String.join("; ", r.failures()) + ")").toList())));
                        continue;
                    }
                }
                endpointService.publish(ws, id, "Applied from configuration", userId);
                outcomes.add(new PublishOutcome(routeKey, "published", null));
            } catch (ApiProblem e) {
                outcomes.add(new PublishOutcome(routeKey, "failed", e.getMessage()));
            }
        }
        for (String routeKey : toDisable) {
            try {
                endpointService.disable(ws, ids.get(routeKey));
                outcomes.add(new PublishOutcome(routeKey, "disabled", null));
            } catch (ApiProblem e) {
                outcomes.add(new PublishOutcome(routeKey, "failed", e.getMessage()));
            }
        }
        return new ConfigApplyResult(plan, outcomes);
    }

    // ------------------------------------------------------------------ parsing

    private List<JsonNode> parse(String file) {
        if (file == null || file.isBlank()) {
            throw ApiProblem.badRequest("invalid_config", "The configuration file is empty.");
        }
        if (file.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiProblem.badRequest("invalid_config", "The configuration file is larger than 5 MB.");
        }
        List<JsonNode> out = new ArrayList<>();
        try (MappingIterator<JsonNode> it = yaml.readerFor(JsonNode.class).readValues(file)) {
            while (it.hasNextValue()) {
                JsonNode n = it.nextValue();
                if (n != null && !n.isNull() && !n.isMissingNode() && !(n.isObject() && n.isEmpty())) {
                    out.add(n);
                }
            }
        } catch (IOException e) {
            throw ApiProblem.badRequest("invalid_config", "The file is not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
        return out;
    }

    private Analysis analyse(List<JsonNode> docs, PlanOptions options) {
        Map<String, String> bindings = new HashMap<>();
        if (options.environment() != null && !options.environment().isBlank()) {
            bindings.putAll(environments.find(options.environment())
                    .orElseThrow(() -> ApiProblem.badRequest("unknown_environment",
                            "Environment '%s' does not exist in this workspace.".formatted(options.environment())))
                    .bindings());
        }
        Map<String, ConfigDocument> desired = new LinkedHashMap<>();
        Set<String> skipped = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode raw : docs) {
            index++;
            ConfigDocument d = normalize(raw, index, bindings);
            if (d == null) {
                continue;
            }
            String id = id(d.kind(), d.key());
            if (ConfigDocument.DATASOURCE.equals(d.kind()) && bindings.containsKey(d.key())) {
                skipped.add(d.key());
                continue;
            }
            if (desired.putIfAbsent(id, d) != null) {
                throw ApiProblem.badRequest("invalid_config", "%s '%s' appears twice in the file.".formatted(d.kind(), d.key()));
            }
        }
        Map<String, ConfigDocument> current = new LinkedHashMap<>();
        for (ConfigDocument d : exporter.documents()) {
            current.put(id(d.kind(), d.key()), d);
        }
        Map<String, Endpoint> endpoints = new HashMap<>();
        for (Endpoint e : endpointStore.list(new EndpointStore.Filter(null, null, null, null, false))) {
            endpoints.put(route(e.method(), e.path()), e);
        }
        return new Analysis(desired, current, skipped, bindings, endpoints);
    }

    /** One document in the exporter's exact shape, so an unchanged workspace plans as unchanged. */
    private ConfigDocument normalize(JsonNode raw, int index, Map<String, String> bindings) {
        String kind = raw.path("kind").asText("");
        String where = "Document %d (%s)".formatted(index, kind.isEmpty() ? "no kind" : kind);
        if (!YamlExporter.API_VERSION.equals(raw.path("apiVersion").asText())) {
            throw invalid(where, "apiVersion must be " + YamlExporter.API_VERSION + ".");
        }
        JsonNode metaIn = raw.path("metadata");
        ObjectNode specIn = raw.path("spec").isObject() ? ((ObjectNode) raw.path("spec")).deepCopy() : json.createObjectNode();
        String name = metaIn.path("name").asText(null);
        try {
            return switch (kind) {
                case ConfigDocument.DATASOURCE -> datasource(requireName(name, where), specIn);
                case ConfigDocument.GROUP -> new ConfigDocument(kind, requireName(name, where), meta(requireName(name, where), null), null);
                case ConfigDocument.SERVICE_ACCOUNT -> serviceAccount(requireName(name, where), specIn);
                case ConfigDocument.ENDPOINT -> endpoint(metaIn, specIn, where, bindings);
                case ConfigDocument.DATA_POLICY -> dataPolicy(specIn, where, bindings);
                case ConfigDocument.GRANT -> grant(specIn, where);
                default -> throw invalid(where, "kind must be one of " + String.join(", ", ConfigDocument.ORDER) + ".");
            };
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid(where + (name != null ? " '" + name + "'" : ""), e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private ConfigDocument datasource(String name, ObjectNode spec) throws JsonProcessingException {
        boolean readOnly = spec.path("readOnly").asBoolean(true);
        String description = text(spec.remove("description"));
        String passwordRef = text(spec.remove("passwordRef"));
        spec.remove("readOnly");
        if (spec.has("password")) {
            throw new IllegalArgumentException("Passwords do not belong in configuration files; use passwordRef: secret:NAME or env:NAME.");
        }
        if (passwordRef != null && !passwordRef.startsWith("env:") && !passwordRef.startsWith(WorkspaceSecrets.PREFIX)
                && !passwordRef.startsWith(io.opendbapi.core.secret.VaultSecrets.PREFIX)) {
            throw new IllegalArgumentException("passwordRef must be secret:NAME, env:NAME or vault:PATH#KEY.");
        }
        boolean http = io.opendbapi.core.datasource.Datasource.HTTP.equals(text(spec.remove("type")));
        ObjectNode out = json.createObjectNode();
        if (http) {
            out.put("type", io.opendbapi.core.datasource.Datasource.HTTP);
            out.setAll((ObjectNode) json.valueToTree(json.treeToValue(spec, io.opendbapi.core.datasource.HttpConfig.class)));
        } else {
            out.setAll((ObjectNode) json.valueToTree(json.treeToValue(spec, JdbcConfig.class)));
        }
        out.put("readOnly", readOnly);
        if (description != null) {
            out.put("description", description);
        }
        if (passwordRef != null) {
            out.put("passwordRef", passwordRef);
        }
        return new ConfigDocument(ConfigDocument.DATASOURCE, name, meta(name, null), strip(out));
    }

    private ConfigDocument serviceAccount(String name, ObjectNode spec) {
        ObjectNode out = json.createObjectNode();
        String description = text(spec.get("description"));
        if (description != null) {
            out.put("description", description);
        }
        out.put("disabled", spec.path("disabled").asBoolean(false));
        out.set("attributes", spec.path("attributes").isObject() ? spec.get("attributes") : json.createObjectNode());
        return new ConfigDocument(ConfigDocument.SERVICE_ACCOUNT, name, meta(name, null), strip(out));
    }

    private ConfigDocument endpoint(JsonNode metaIn, ObjectNode spec, String where, Map<String, String> bindings) throws JsonProcessingException {
        String method = text(spec.remove("method"));
        String path = text(spec.remove("path"));
        if (method == null || path == null) {
            throw invalid(where, "an endpoint needs spec.method and spec.path.");
        }
        method = method.toUpperCase(java.util.Locale.ROOT);
        path = path.startsWith("/") ? path.substring(1) : path;
        String access = Objects.requireNonNullElse(text(spec.remove("access")), "private");
        String description = text(spec.remove("description"));
        String datasource = text(spec.remove("datasource"));
        JsonNode testsIn = spec.remove("tests");
        if (spec.path("task").isObject()) {
            ((ObjectNode) spec.get("task")).remove("datasourceId");
        }
        EndpointDefinition definition = json.treeToValue(spec, EndpointDefinition.class);
        ObjectNode out = json.createObjectNode();
        out.put("method", method);
        out.put("path", path);
        out.put("access", access);
        if (description != null) {
            out.put("description", description);
        }
        if (datasource != null) {
            out.put("datasource", bindings.getOrDefault(datasource, datasource));
        }
        ObjectNode defTree = json.valueToTree(definition);
        ((ObjectNode) defTree.path("task")).remove("datasourceId");
        out.setAll(defTree);
        if (testsIn != null && testsIn.isArray() && !testsIn.isEmpty()) {
            ArrayNode array = out.putArray("tests");
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode t : testsIn) {
                String testName = text(t.get("name"));
                if (testName == null || !names.add(testName)) {
                    throw invalid(where, "every test needs a unique name.");
                }
                ObjectNode tc = array.addObject();
                tc.put("name", testName);
                String sa = text(t.get("serviceAccount"));
                if (sa != null) {
                    tc.put("serviceAccount", sa);
                }
                tc.set("params", t.path("params").isObject() ? t.get("params") : json.createObjectNode());
                tc.set("expect", json.valueToTree(json.treeToValue(t.path("expect").isObject() ? t.get("expect") : json.createObjectNode(),
                        EndpointTestCase.TestExpectation.class)));
            }
        }
        String routeKey = route(method, path);
        ObjectNode metadata = meta(Objects.requireNonNullElse(text(metaIn.get("name")), routeKey), text(metaIn.get("group")));
        String state = text(metaIn.get("state"));
        if (state != null) {
            if (!Set.of(Endpoint.DRAFT, Endpoint.LIVE, Endpoint.DISABLED).contains(state)) {
                throw invalid(where, "metadata.state must be draft, live or disabled.");
            }
            metadata.put("state", state);
        }
        // Spec §16: lineage and deprecation, in the exporter's shape.
        String majorOf = text(metaIn.get("majorOf"));
        if (majorOf != null) {
            String rootRoute = normalizeRoute(majorOf, where);
            if (!rootRoute.equals(routeKey)) {
                metadata.put("majorOf", rootRoute);
            }
        }
        int major = metaIn.path("major").asInt(1);
        if (major < 1) {
            throw invalid(where, "metadata.major is 1 or more.");
        }
        if (major > 1) {
            metadata.put("major", major);
        }
        if (metaIn.path("deprecated").asBoolean(false)) {
            metadata.put("deprecated", true);
        }
        String sunset = text(metaIn.get("sunset"));
        if (sunset != null) {
            try {
                metadata.put("sunset", java.time.OffsetDateTime.parse(sunset).toInstant().toString());
            } catch (java.time.format.DateTimeParseException e) {
                try {
                    metadata.put("sunset", java.time.Instant.parse(sunset).toString());
                } catch (java.time.format.DateTimeParseException e2) {
                    throw invalid(where, "metadata.sunset is a date-time such as 2027-06-30T00:00:00Z.");
                }
            }
        }
        return new ConfigDocument(ConfigDocument.ENDPOINT, routeKey, metadata, strip(out));
    }

    private ConfigDocument dataPolicy(ObjectNode spec, String where, Map<String, String> bindings) throws JsonProcessingException {
        String kind = text(spec.get("kind"));
        String column = text(spec.get("column"));
        String datasource = text(spec.get("datasource"));
        String endpoint = text(spec.get("endpoint"));
        if (kind == null || column == null || (datasource == null) == (endpoint == null)) {
            throw invalid(where, "a data policy needs kind, column and exactly one of datasource or endpoint.");
        }
        if (datasource != null) {
            datasource = bindings.getOrDefault(datasource, datasource);
        }
        if (endpoint != null) {
            endpoint = normalizeRoute(endpoint, where);
        }
        ObjectNode out = json.createObjectNode().put("kind", kind).put("column", column);
        if (datasource != null) {
            out.put("datasource", datasource);
        }
        if (endpoint != null) {
            out.put("endpoint", endpoint);
        }
        if (spec.path("mask").isObject()) {
            out.set("mask", json.valueToTree(json.treeToValue(spec.get("mask"), DataPolicy.Mask.class)));
        }
        String attribute = text(spec.get("attribute"));
        if (attribute != null) {
            out.put("attribute", attribute);
        }
        ArrayNode exempt = out.putArray("exemptServiceAccounts");
        spec.path("exemptServiceAccounts").forEach(n -> exempt.add(n.asText()));
        return new ConfigDocument(ConfigDocument.DATA_POLICY, ConfigDocument.policyKey(kind, column, datasource, endpoint), null, strip(out));
    }

    private ConfigDocument grant(ObjectNode spec, String where) {
        String sa = text(spec.get("serviceAccount"));
        String endpoint = text(spec.get("endpoint"));
        String group = text(spec.get("group"));
        if (sa == null || (endpoint == null) == (group == null)) {
            throw invalid(where, "a grant needs serviceAccount and exactly one of endpoint or group.");
        }
        String type = endpoint != null ? "endpoint" : "group";
        String target = endpoint != null ? normalizeRoute(endpoint, where) : group;
        ObjectNode out = json.createObjectNode().put("serviceAccount", sa).put(type, target);
        return new ConfigDocument(ConfigDocument.GRANT, ConfigDocument.grantKey(sa, type, target), null, out);
    }

    // ------------------------------------------------------------------ diff

    private ConfigPlan diff(Analysis a, PlanOptions options) {
        List<ConfigChange> changes = new ArrayList<>();
        for (String kind : ConfigDocument.ORDER) {
            if (ConfigDocument.DATASOURCE.equals(kind)) {
                for (String s : a.skipped()) {
                    changes.add(new ConfigChange(kind, s, SKIP, List.of("bound to datasource '%s' in environment %s"
                            .formatted(a.bindings().get(s), options.environment()))));
                }
            }
            for (ConfigDocument d : a.desired().values()) {
                if (!d.kind().equals(kind)) {
                    continue;
                }
                ConfigDocument c = a.current().get(id(kind, d.key()));
                List<String> details = new ArrayList<>(c == null ? List.of() : differences(c, d));
                if (ConfigDocument.ENDPOINT.equals(kind)) {
                    details.remove("metadata.state");
                    if (options.publish()) {
                        details.addAll(stateChanges(a.endpoints().get(d.key()), d, c == null || !details.isEmpty()));
                    }
                }
                String action = c == null ? CREATE : details.isEmpty() ? UNCHANGED : UPDATE;
                changes.add(new ConfigChange(kind, d.key(), action, List.copyOf(details)));
            }
        }
        if (options.prune()) {
            Set<String> bound = new java.util.HashSet<>(a.bindings().values());
            List<String> reversed = new ArrayList<>(ConfigDocument.ORDER);
            java.util.Collections.reverse(reversed);
            for (String kind : reversed) {
                for (ConfigDocument c : a.current().values()) {
                    if (c.kind().equals(kind) && !a.desired().containsKey(id(kind, c.key()))
                            && !(ConfigDocument.DATASOURCE.equals(kind) && (bound.contains(c.key()) || a.skipped().contains(c.key())))) {
                        changes.add(new ConfigChange(kind, c.key(), DELETE, List.of()));
                    }
                }
            }
        }
        Map<String, Integer> counts = new TreeMap<>();
        changes.forEach(c -> counts.merge(c.action(), 1, Integer::sum));
        return new ConfigPlan(options.environment(), options.prune(), options.publish(), changes, counts, digest(a, options));
    }

    /** With {@code publish}: what the endpoint's live state needs. */
    private static List<String> stateChanges(Endpoint current, ConfigDocument d, boolean draftChanges) {
        String want = d.metadata().path("state").asText(null);
        if (Endpoint.LIVE.equals(want)) {
            boolean upToDate = current != null && Endpoint.LIVE.equals(current.state()) && !current.dirty() && !draftChanges;
            return upToDate ? List.of() : List.of("publish");
        }
        if (Endpoint.DISABLED.equals(want) && current != null && Endpoint.LIVE.equals(current.state())) {
            return List.of("disable");
        }
        return List.of();
    }

    private static List<String> differences(ConfigDocument current, ConfigDocument desired) {
        List<String> out = new ArrayList<>();
        compareFields("metadata", current.metadata(), desired.metadata(), out);
        compareFields("spec", current.spec(), desired.spec(), out);
        return out;
    }

    private static void compareFields(String prefix, ObjectNode a, ObjectNode b, List<String> out) {
        Set<String> fields = new java.util.TreeSet<>();
        if (a != null) {
            a.fieldNames().forEachRemaining(fields::add);
        }
        if (b != null) {
            b.fieldNames().forEachRemaining(fields::add);
        }
        for (String f : fields) {
            JsonNode x = a == null ? null : a.get(f);
            JsonNode y = b == null ? null : b.get(f);
            if (!Objects.equals(x, y)) {
                out.add(prefix + "." + f);
            }
        }
    }

    private String digest(Analysis a, PlanOptions options) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((options.environment() + "|" + options.prune() + "|" + options.publish()).getBytes(StandardCharsets.UTF_8));
            for (Map<String, ConfigDocument> side : List.of(a.desired(), a.current())) {
                for (var e : new TreeMap<>(side).entrySet()) {
                    sha.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    sha.update(json.writeValueAsBytes(canonical(json.valueToTree(e.getValue()))));
                }
            }
            for (var e : new TreeMap<>(a.endpoints()).entrySet()) {
                sha.update((e.getKey() + e.getValue().state() + e.getValue().dirty()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest(), 0, 16);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            new TreeMap<String, JsonNode>() {{
                n.fields().forEachRemaining(e -> put(e.getKey(), e.getValue()));
            }}.forEach((k, v) -> sorted.set(k, canonical(v)));
            return sorted;
        }
        if (n.isArray()) {
            ArrayNode out = json.createArrayNode();
            n.forEach(x -> out.add(canonical(x)));
            return out;
        }
        return n;
    }

    // ------------------------------------------------------------------ apply

    private void write(UUID ws, Analysis a, ConfigPlan plan, UUID userId, List<UUID> evict) {
        Map<String, Datasource> dsByName = new HashMap<>();
        datasources.list().forEach(d -> dsByName.put(d.name(), d));
        Map<String, UUID> groupIds = new HashMap<>();
        groups.list().forEach(g -> groupIds.put(g.name(), g.id()));
        Map<String, ServiceAccountStore.ServiceAccount> saByName = new HashMap<>();
        accounts.list().forEach(s -> saByName.put(s.name(), s));

        for (ConfigChange c : plan.changes()) {
            if (!CREATE.equals(c.action()) && !UPDATE.equals(c.action())) {
                continue;
            }
            ConfigDocument d = a.desired().get(id(c.kind(), c.name()));
            ObjectNode spec = d.spec() == null ? json.createObjectNode() : d.spec().deepCopy();
            switch (c.kind()) {
                case ConfigDocument.DATASOURCE -> {
                    String description = text(spec.remove("description"));
                    String passwordRef = text(spec.remove("passwordRef"));
                    boolean readOnly = spec.remove("readOnly").asBoolean(true);
                    String type = Datasource.HTTP.equals(text(spec.remove("type"))) ? Datasource.HTTP : Datasource.JDBC;
                    Object config = Datasource.HTTP.equals(type)
                            ? treeToValue(spec, io.opendbapi.core.datasource.HttpConfig.class, c).validate()
                            : treeToValue(spec, JdbcConfig.class, c);
                    String secretRef = passwordRef == null ? null : workspaceSecrets.toReference(passwordRef);
                    Datasource existing = dsByName.get(c.name());
                    if (existing != null && !existing.type().equals(type)) {
                        throw ApiProblem.conflict("datasource_type", "Datasource '%s' is %s here and %s in the files; delete it first."
                                .formatted(c.name(), existing.type(), type));
                    }
                    Datasource saved = existing == null
                            ? datasources.create(ws, c.name(), description, type, config, secretRef, readOnly, userId)
                            : datasources.update(existing.id(), c.name(), description, config, secretRef, readOnly);
                    dsByName.put(c.name(), saved);
                    if (existing != null) {
                        evict.add(existing.id());
                    }
                }
                case ConfigDocument.GROUP -> groupIds.put(c.name(), groups.create(ws, c.name()).id());
                case ConfigDocument.SERVICE_ACCOUNT -> {
                    String description = text(spec.get("description"));
                    boolean disabled = spec.path("disabled").asBoolean(false);
                    Map<String, Object> attributes = json.convertValue(spec.path("attributes").isObject() ? spec.get("attributes")
                            : json.createObjectNode(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
                    ServiceAccountStore.ServiceAccount existing = saByName.get(c.name());
                    UUID id = existing == null ? accounts.create(ws, c.name(), description, attributes, userId) : existing.id();
                    accounts.update(id, c.name(), description, disabled, attributes);
                    saByName.put(c.name(), accounts.find(id).orElseThrow());
                }
                case ConfigDocument.ENDPOINT -> writeEndpoint(ws, a, d, spec, dsByName, groupIds, saByName, userId);
                case ConfigDocument.DATA_POLICY -> writePolicy(ws, a, d, spec, dsByName, saByName, userId);
                case ConfigDocument.GRANT -> {
                    ServiceAccountStore.ServiceAccount sa = saByName.get(spec.path("serviceAccount").asText());
                    if (sa == null) {
                        throw unresolved(c, "service account '" + spec.path("serviceAccount").asText() + "'");
                    }
                    if (spec.has("endpoint")) {
                        accounts.grant(ws, sa.id(), "endpoint", endpointId(spec.path("endpoint").asText(), c), userId);
                    } else {
                        UUID g = groupIds.get(spec.path("group").asText());
                        if (g == null) {
                            throw unresolved(c, "group '" + spec.path("group").asText() + "'");
                        }
                        accounts.grant(ws, sa.id(), "group", g, userId);
                    }
                }
                default -> throw new IllegalStateException(c.kind());
            }
        }
        // Spec §16: lineages and deprecation, once every endpoint of the file exists.
        for (ConfigChange c : plan.changes()) {
            if (ConfigDocument.ENDPOINT.equals(c.kind()) && (CREATE.equals(c.action()) || UPDATE.equals(c.action()))) {
                ConfigDocument d = a.desired().get(id(c.kind(), c.name()));
                Endpoint e = endpointStore.find(endpointId(c.name(), c)).orElseThrow();
                String rootRoute = text(d.metadata().get("majorOf"));
                UUID lineage = rootRoute == null ? e.id() : endpointStore.find(endpointId(rootRoute, c)).orElseThrow().lineageId();
                endpointStore.setLineage(e.id(), lineage, d.metadata().path("major").asInt(1));
                boolean deprecated = d.metadata().path("deprecated").asBoolean(false);
                String sunset = text(d.metadata().get("sunset"));
                endpointStore.setDeprecation(e.id(),
                        deprecated ? (e.deprecatedAt() != null ? e.deprecatedAt() : java.time.OffsetDateTime.now()) : null,
                        sunset == null ? null : java.time.OffsetDateTime.parse(sunset));
            }
        }
        for (ConfigChange c : plan.changes()) {
            if (DELETE.equals(c.action())) {
                delete(ws, a.current().get(id(c.kind(), c.name())), dsByName, groupIds, saByName, c);
            }
        }
    }

    private void writeEndpoint(UUID ws, Analysis a, ConfigDocument d, ObjectNode spec, Map<String, Datasource> dsByName,
                               Map<String, UUID> groupIds, Map<String, ServiceAccountStore.ServiceAccount> saByName, UUID userId) {
        ConfigChange c = new ConfigChange(d.kind(), d.key(), null, List.of());
        String method = text(spec.remove("method"));
        String path = text(spec.remove("path"));
        String access = text(spec.remove("access"));
        String description = text(spec.remove("description"));
        String dsName = text(spec.remove("datasource"));
        JsonNode testsIn = spec.remove("tests");
        if (dsName != null) {
            Datasource ds = dsByName.get(dsName);
            if (ds == null) {
                throw unresolved(c, "datasource '" + dsName + "'");
            }
            ObjectNode task = spec.get("task") instanceof ObjectNode t ? t : spec.putObject("task");
            task.put("datasourceId", ds.id().toString());
        }
        EndpointDefinition definition = treeToValue(spec, EndpointDefinition.class, c);
        String groupName = text(d.metadata().get("group"));
        UUID groupId = null;
        if (groupName != null) {
            groupId = groupIds.get(groupName);
            if (groupId == null) {
                throw unresolved(c, "group '" + groupName + "'");
            }
        }
        var draft = new EndpointService.Draft(groupId, d.metadata().path("name").asText(), description, method, path, access, definition);
        Endpoint existing = a.endpoints().get(d.key());
        UUID id = existing == null ? endpointService.create(ws, draft, userId).id() : endpointService.update(ws, existing.id(), draft).id();

        Map<String, EndpointTestCase> saved = new HashMap<>();
        testStore.list(id).forEach(t -> saved.put(t.name(), t));
        Set<String> wanted = new LinkedHashSet<>();
        if (testsIn != null) {
            for (JsonNode t : testsIn) {
                String name = t.path("name").asText();
                wanted.add(name);
                UUID saId = null;
                String sa = text(t.get("serviceAccount"));
                if (sa != null) {
                    ServiceAccountStore.ServiceAccount account = saByName.get(sa);
                    if (account == null) {
                        throw unresolved(c, "service account '" + sa + "' of test '" + name + "'");
                    }
                    saId = account.id();
                }
                Map<String, Object> params = json.convertValue(t.path("params").isObject() ? t.get("params") : json.createObjectNode(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                var expect = treeToValue(t.path("expect").isObject() ? t.get("expect") : json.createObjectNode(),
                        EndpointTestCase.TestExpectation.class, c);
                var tc = new EndpointTestCase(null, name, saId, params, expect, null, null, null);
                EndpointTestCase old = saved.get(name);
                if (old == null) {
                    testStore.create(ws, id, tc, userId);
                } else {
                    testStore.update(id, old.id(), tc);
                }
            }
        }
        for (EndpointTestCase old : saved.values()) {
            if (!wanted.contains(old.name())) {
                testStore.delete(id, old.id());
            }
        }
    }

    private void writePolicy(UUID ws, Analysis a, ConfigDocument d, ObjectNode spec, Map<String, Datasource> dsByName,
                             Map<String, ServiceAccountStore.ServiceAccount> saByName, UUID userId) {
        ConfigChange c = new ConfigChange(d.kind(), d.key(), null, List.of());
        UUID dsId = null;
        UUID epId = null;
        if (spec.has("datasource")) {
            Datasource ds = dsByName.get(spec.path("datasource").asText());
            if (ds == null) {
                throw unresolved(c, "datasource '" + spec.path("datasource").asText() + "'");
            }
            dsId = ds.id();
        } else {
            epId = endpointId(spec.path("endpoint").asText(), c);
        }
        List<UUID> exempt = new ArrayList<>();
        for (JsonNode n : spec.path("exemptServiceAccounts")) {
            ServiceAccountStore.ServiceAccount sa = saByName.get(n.asText());
            if (sa == null) {
                throw unresolved(c, "service account '" + n.asText() + "'");
            }
            exempt.add(sa.id());
        }
        DataPolicy.Mask mask = spec.path("mask").isObject() ? treeToValue(spec.get("mask"), DataPolicy.Mask.class, c) : null;
        DataPolicy p = new DataPolicy(null, spec.path("kind").asText(), dsId, epId, spec.path("column").asText(), mask,
                text(spec.get("attribute")), exempt, null, null);
        DataPolicies.check(p);
        UUID existing = null;
        for (DataPolicyStore.Stored s : policyStore.all()) {
            DataPolicy q = s.policy();
            if (q.kind().equals(p.kind()) && q.column().equals(p.column()) && Objects.equals(q.datasourceId(), dsId)
                    && Objects.equals(q.endpointId(), epId)) {
                existing = q.id();
            }
        }
        if (existing == null) {
            policyStore.create(ws, p, userId);
        } else {
            policyStore.update(existing, p);
        }
    }

    private void delete(UUID ws, ConfigDocument d, Map<String, Datasource> dsByName, Map<String, UUID> groupIds,
                        Map<String, ServiceAccountStore.ServiceAccount> saByName, ConfigChange c) {
        ObjectNode spec = d.spec();
        switch (d.kind()) {
            case ConfigDocument.GRANT -> {
                ServiceAccountStore.ServiceAccount sa = saByName.get(spec.path("serviceAccount").asText());
                if (sa != null) {
                    UUID target = spec.has("endpoint") ? endpointId(spec.path("endpoint").asText(), c) : groupIds.get(spec.path("group").asText());
                    accounts.revokeGrant(sa.id(), spec.has("endpoint") ? "endpoint" : "group", target);
                }
            }
            case ConfigDocument.DATA_POLICY -> {
                for (DataPolicyStore.Stored s : policyStore.all()) {
                    DataPolicy q = s.policy();
                    String ds = q.datasourceId() == null ? null : dsByName.values().stream()
                            .filter(x -> x.id().equals(q.datasourceId())).map(Datasource::name).findFirst().orElse(null);
                    String ep = q.endpointId() == null ? null : endpointStore.find(q.endpointId())
                            .map(e -> route(e.method(), e.path())).orElse(null);
                    if (ConfigDocument.policyKey(q.kind(), q.column(), ds, ep).equals(d.key())) {
                        policyStore.delete(q.id());
                    }
                }
            }
            case ConfigDocument.ENDPOINT -> {
                UUID id = endpointId(d.key(), c);
                Endpoint e = endpointStore.find(id).orElseThrow();
                if (Endpoint.LIVE.equals(e.state())) {
                    endpointService.disable(ws, id);
                }
                endpointService.trash(ws, id);
            }
            case ConfigDocument.SERVICE_ACCOUNT -> accounts.delete(saByName.get(d.key()).id());
            case ConfigDocument.GROUP -> groups.delete(groupIds.get(d.key()));
            case ConfigDocument.DATASOURCE -> {
                Datasource ds = dsByName.get(d.key());
                List<String> users = datasources.endpointsUsing(ds.id());
                if (!users.isEmpty()) {
                    throw ApiProblem.conflict("datasource_in_use",
                            "Datasource '%s' is still used by %s.".formatted(d.key(), String.join(", ", users)));
                }
                datasources.delete(ds.id());
            }
            default -> throw new IllegalStateException(d.kind());
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID endpointId(String routeKey, ConfigChange c) {
        for (Endpoint e : endpointStore.list(new EndpointStore.Filter(null, null, null, null, false))) {
            if (route(e.method(), e.path()).equals(routeKey)) {
                return e.id();
            }
        }
        throw unresolved(c, "endpoint '" + routeKey + "'");
    }

    private <T> T treeToValue(JsonNode node, Class<T> type, ConfigChange c) {
        try {
            return json.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid(c.kind() + " '" + c.name() + "'", e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private ObjectNode meta(String name, String group) {
        ObjectNode m = json.createObjectNode().put("name", name);
        if (group != null) {
            m.put("group", group);
        }
        return m;
    }

    private static ObjectNode strip(ObjectNode n) {
        return (ObjectNode) YamlExporter.stripNulls(n);
    }

    static String route(String method, String path) {
        return method + " /" + path;
    }

    private static String normalizeRoute(String routeKey, String where) {
        String[] parts = routeKey.strip().split("\\s+", 2);
        if (parts.length != 2) {
            throw invalid(where, "an endpoint is referred to as 'METHOD /path', got '" + routeKey + "'.");
        }
        String path = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        return route(parts[0].toUpperCase(java.util.Locale.ROOT), path);
    }

    private static String id(String kind, String key) {
        return kind + "\n" + key;
    }

    private static String requireName(String name, String where) {
        if (name == null || name.isBlank()) {
            throw invalid(where, "metadata.name is required.");
        }
        return name;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private static ApiProblem invalid(String where, String message) {
        return ApiProblem.badRequest("invalid_config", where + ": " + message);
    }

    private static ApiProblem unresolved(ConfigChange c, String what) {
        return ApiProblem.badRequest("invalid_config", "%s '%s' refers to %s, which is neither in the file nor in the workspace."
                .formatted(c.kind(), c.name(), what));
    }
}
