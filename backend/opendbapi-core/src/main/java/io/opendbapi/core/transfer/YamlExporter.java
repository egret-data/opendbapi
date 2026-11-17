package io.opendbapi.core.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.datapolicy.DataPolicy;
import io.opendbapi.core.datapolicy.DataPolicyStore;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.endpoint.ApiGroupStore;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.test.EndpointTestCase;
import io.opendbapi.core.endpoint.test.EndpointTestStore;
import io.opendbapi.core.workspace.WorkspaceScope;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes a workspace as YAML documents (spec §15.1): Datasource, Group, Endpoint (the saved
 * draft, with its tests), ServiceAccount, Grant and DataPolicy. References use names, not ids,
 * so the file reads well in a review and can later be applied to another environment.
 * Secrets never appear: a datasource shows its {@code env:}/{@code secret:} reference at most,
 * service accounts carry no clients. {@link ConfigPlanner} compares these documents with a file.
 */
@Service
public class YamlExporter {

    public static final String API_VERSION = "opendbapi/v1";

    private final WorkspaceScope scope;
    private final DatasourceStore datasources;
    private final ApiGroupStore groups;
    private final EndpointStore endpoints;
    private final EndpointTestStore tests;
    private final ServiceAccountStore accounts;
    private final DataPolicyStore policies;
    private final ObjectMapper json;
    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .build());

    public YamlExporter(WorkspaceScope scope, DatasourceStore datasources, ApiGroupStore groups, EndpointStore endpoints,
                        EndpointTestStore tests, ServiceAccountStore accounts, DataPolicyStore policies, ObjectMapper json) {
        this.scope = scope;
        this.datasources = datasources;
        this.groups = groups;
        this.endpoints = endpoints;
        this.tests = tests;
        this.accounts = accounts;
        this.policies = policies;
        this.json = json;
    }

    public String export(UUID workspaceId) {
        StringWriter out = new StringWriter();
        out.write("# OpenDBAPI workspace export. Secrets are not included.\n");
        for (ConfigDocument d : scope.call(workspaceId, this::documents)) {
            write(out, d);
        }
        return out.toString();
    }

    /** The workspace as documents, in {@link ConfigDocument#ORDER}; call inside the workspace scope. */
    public List<ConfigDocument> documents() {
        List<ConfigDocument> docs = new ArrayList<>();
        Map<UUID, String> dsNames = new HashMap<>();
        for (Datasource d : datasources.list()) {
            dsNames.put(d.id(), d.name());
            docs.add(doc(ConfigDocument.DATASOURCE, d.name(), meta(d.name(), null), datasourceSpec(d)));
        }
        Map<UUID, String> groupNames = new HashMap<>();
        for (ApiGroupStore.ApiGroup g : groups.list()) {
            groupNames.put(g.id(), g.name());
            docs.add(doc(ConfigDocument.GROUP, g.name(), meta(g.name(), null), null));
        }
        Map<UUID, String> accountNames = new HashMap<>();
        List<ServiceAccountStore.ServiceAccount> accountList = accounts.list();
        for (ServiceAccountStore.ServiceAccount sa : accountList) {
            accountNames.put(sa.id(), sa.name());
            ObjectNode spec = json.createObjectNode();
            if (sa.description() != null) {
                spec.put("description", sa.description());
            }
            spec.put("disabled", sa.disabled());
            spec.set("attributes", json.valueToTree(sa.attributes()));
            docs.add(doc(ConfigDocument.SERVICE_ACCOUNT, sa.name(), meta(sa.name(), null), spec));
        }
        Map<UUID, String> endpointNames = new HashMap<>();
        List<Endpoint> endpointList = endpoints.list(new EndpointStore.Filter(null, null, null, null, false));
        // Spec §16: a lineage is named by its first major's route, which is the same in every environment.
        Map<UUID, Endpoint> lineageRoots = new HashMap<>();
        for (Endpoint e : endpointList) {
            lineageRoots.merge(e.lineageId(), e, (a, b) -> a.major() <= b.major() ? a : b);
        }
        for (Endpoint e : endpointList) {
            String route = e.method() + " /" + e.path();
            endpointNames.put(e.id(), route);
            ObjectNode spec = endpointSpec(e.method(), e.path(), e.access(), e.description(), json.valueToTree(e.definition()), dsNames);
            List<EndpointTestCase> cases = tests.list(e.id());
            if (!cases.isEmpty()) {
                var array = spec.putArray("tests");
                for (EndpointTestCase t : cases) {
                    ObjectNode tc = array.addObject();
                    tc.put("name", t.name());
                    if (t.serviceAccountId() != null) {
                        tc.put("serviceAccount", accountNames.getOrDefault(t.serviceAccountId(), t.serviceAccountId().toString()));
                    }
                    tc.set("params", json.valueToTree(t.params()));
                    tc.set("expect", json.valueToTree(t.expect()));
                }
            }
            ObjectNode metadata = meta(e.name(), e.groupId() == null ? null : groupNames.get(e.groupId()));
            metadata.put("state", e.state());
            Endpoint root = lineageRoots.get(e.lineageId());
            if (root != null && !root.id().equals(e.id())) {
                metadata.put("majorOf", root.method() + " /" + root.path());
            }
            if (e.major() > 1) {
                metadata.put("major", e.major());
            }
            if (e.deprecatedAt() != null) {
                metadata.put("deprecated", true);
            }
            if (e.sunsetAt() != null) {
                metadata.put("sunset", e.sunsetAt().toInstant().toString());
            }
            docs.add(doc(ConfigDocument.ENDPOINT, route, metadata, spec));
        }
        for (DataPolicyStore.Stored s : policies.all()) {
            DataPolicy p = s.policy();
            ObjectNode spec = json.createObjectNode().put("kind", p.kind()).put("column", p.column());
            String ds = p.datasourceId() == null ? null : dsNames.get(p.datasourceId());
            String ep = p.endpointId() == null ? null : endpointNames.get(p.endpointId());
            if (ds != null) {
                spec.put("datasource", ds);
            }
            if (ep != null) {
                spec.put("endpoint", ep);
            }
            if (p.mask() != null) {
                spec.set("mask", json.valueToTree(p.mask()));
            }
            if (p.attribute() != null) {
                spec.put("attribute", p.attribute());
            }
            var exempt = spec.putArray("exemptServiceAccounts");
            p.exemptServiceAccounts().forEach(id -> exempt.add(accountNames.getOrDefault(id, id.toString())));
            docs.add(doc(ConfigDocument.DATA_POLICY, ConfigDocument.policyKey(p.kind(), p.column(), ds, ep), null, spec));
        }
        for (ServiceAccountStore.ServiceAccount sa : accountList) {
            for (ServiceAccountStore.Grant g : accounts.grants(sa.id())) {
                String target = "endpoint".equals(g.resourceType()) && g.detail() != null ? g.detail() : g.resourceName();
                ObjectNode grant = json.createObjectNode().put("serviceAccount", sa.name()).put(g.resourceType(), target);
                docs.add(doc(ConfigDocument.GRANT, ConfigDocument.grantKey(sa.name(), g.resourceType(), target), null, grant));
            }
        }
        return docs;
    }

    /** A datasource as its document shows it: settings, never a password; only env:/secret: references. */
    ObjectNode datasourceSpec(Datasource d) {
        ObjectNode spec = json.valueToTree(d.config());
        spec.put("readOnly", d.readOnly());
        if (d.description() != null) {
            spec.put("description", d.description());
        }
        if (d.secretRef() != null && (d.secretRef().startsWith("env:") || d.secretRef().startsWith("secret:")
                || d.secretRef().startsWith("vault:"))) {
            spec.put("passwordRef", d.secretRef());
        }
        return spec;
    }

    /** An endpoint's draft as its document shows it: the datasource by name instead of id. */
    ObjectNode endpointSpec(String method, String path, String access, String description, ObjectNode definition, Map<UUID, String> dsNames) {
        ObjectNode spec = json.createObjectNode();
        spec.put("method", method);
        spec.put("path", path);
        spec.put("access", access);
        if (description != null) {
            spec.put("description", description);
        }
        ObjectNode task = (ObjectNode) definition.path("task");
        JsonNode dsId = task.remove("datasourceId");
        if (dsId != null && !dsId.isNull()) {
            spec.put("datasource", dsNames.getOrDefault(UUID.fromString(dsId.asText()), dsId.asText()));
        }
        spec.setAll(definition);
        return spec;
    }

    private static ConfigDocument doc(String kind, String key, ObjectNode metadata, ObjectNode spec) {
        return new ConfigDocument(kind, key, metadata, spec == null ? null : (ObjectNode) stripNulls(spec));
    }

    private ObjectNode meta(String name, String group) {
        ObjectNode m = json.createObjectNode().put("name", name);
        if (group != null) {
            m.put("group", group);
        }
        return m;
    }

    private void write(StringWriter out, ConfigDocument d) {
        ObjectNode doc = json.createObjectNode().put("apiVersion", API_VERSION).put("kind", d.kind());
        if (d.metadata() != null) {
            doc.set("metadata", d.metadata());
        }
        if (d.spec() != null) {
            doc.set("spec", d.spec());
        }
        try {
            out.write("---\n");
            out.write(yaml.writeValueAsString(doc));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Leaves out nulls and empty containers so the file shows only what was set. */
    static JsonNode stripNulls(JsonNode node) {
        if (node instanceof ObjectNode o) {
            var it = o.fields();
            while (it.hasNext()) {
                var e = it.next();
                stripNulls(e.getValue());
                if (e.getValue().isNull() || (e.getValue().isContainerNode() && e.getValue().isEmpty())) {
                    it.remove();
                }
            }
        } else if (node.isArray()) {
            node.forEach(YamlExporter::stripNulls);
        }
        return node;
    }
}
