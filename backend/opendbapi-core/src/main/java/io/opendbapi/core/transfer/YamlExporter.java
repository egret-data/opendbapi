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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes a workspace as YAML documents (spec §15.1): Datasource, Group, Endpoint (the saved
 * draft, with its tests), ServiceAccount, Grant and DataPolicy. References use names, not ids,
 * so the file reads well in a review and can later be applied to another environment.
 * Secrets never appear: a datasource shows its {@code env:} reference at most, service
 * accounts carry no clients.
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
        return scope.call(workspaceId, () -> {
            StringWriter out = new StringWriter();
            out.write("# OpenDBAPI workspace export. Secrets are not included.\n");
            Map<UUID, String> dsNames = new HashMap<>();
            for (Datasource d : datasources.list()) {
                dsNames.put(d.id(), d.name());
                ObjectNode spec = json.valueToTree(d.config());
                spec.put("readOnly", d.readOnly());
                if (d.description() != null) {
                    spec.put("description", d.description());
                }
                if (d.secretRef() != null && d.secretRef().startsWith("env:")) {
                    spec.put("passwordRef", d.secretRef());
                }
                write(out, "Datasource", meta(d.name(), null), spec);
            }
            Map<UUID, String> groupNames = new HashMap<>();
            for (ApiGroupStore.ApiGroup g : groups.list()) {
                groupNames.put(g.id(), g.name());
                write(out, "Group", meta(g.name(), null), null);
            }
            Map<UUID, String> endpointNames = new HashMap<>();
            for (Endpoint e : endpoints.list(new EndpointStore.Filter(null, null, null, null, false))) {
                endpointNames.put(e.id(), e.method() + " /" + e.path());
                ObjectNode spec = json.createObjectNode();
                spec.put("method", e.method());
                spec.put("path", e.path());
                spec.put("access", e.access());
                if (e.description() != null) {
                    spec.put("description", e.description());
                }
                ObjectNode definition = json.valueToTree(e.definition());
                ObjectNode task = (ObjectNode) definition.path("task");
                JsonNode dsId = task.remove("datasourceId");
                if (dsId != null && !dsId.isNull()) {
                    spec.put("datasource", dsNames.getOrDefault(UUID.fromString(dsId.asText()), dsId.asText()));
                }
                spec.setAll(definition);
                List<EndpointTestCase> cases = tests.list(e.id());
                if (!cases.isEmpty()) {
                    var array = spec.putArray("tests");
                    for (EndpointTestCase t : cases) {
                        ObjectNode tc = array.addObject();
                        tc.put("name", t.name());
                        tc.set("params", json.valueToTree(t.params()));
                        tc.set("expect", json.valueToTree(t.expect()));
                    }
                }
                ObjectNode metadata = meta(e.name(), e.groupId() == null ? null : groupNames.get(e.groupId()));
                metadata.put("state", e.state());
                write(out, "Endpoint", metadata, spec);
            }
            Map<UUID, String> accountNames = new HashMap<>();
            for (ServiceAccountStore.ServiceAccount sa : accounts.list()) {
                accountNames.put(sa.id(), sa.name());
                ObjectNode spec = json.createObjectNode();
                if (sa.description() != null) {
                    spec.put("description", sa.description());
                }
                spec.put("disabled", sa.disabled());
                spec.set("attributes", json.valueToTree(sa.attributes()));
                write(out, "ServiceAccount", meta(sa.name(), null), spec);
                for (ServiceAccountStore.Grant g : accounts.grants(sa.id())) {
                    ObjectNode grant = json.createObjectNode().put("serviceAccount", sa.name());
                    grant.put(g.resourceType(), "endpoint".equals(g.resourceType()) && g.detail() != null ? g.detail() : g.resourceName());
                    write(out, "Grant", null, grant);
                }
            }
            for (DataPolicyStore.Stored s : policies.all()) {
                DataPolicy p = s.policy();
                ObjectNode spec = json.createObjectNode().put("kind", p.kind()).put("column", p.column());
                if (p.datasourceId() != null) {
                    spec.put("datasource", dsNames.get(p.datasourceId()));
                }
                if (p.endpointId() != null) {
                    spec.put("endpoint", endpointNames.get(p.endpointId()));
                }
                if (p.mask() != null) {
                    spec.set("mask", json.valueToTree(p.mask()));
                }
                if (p.attribute() != null) {
                    spec.put("attribute", p.attribute());
                }
                var exempt = spec.putArray("exemptServiceAccounts");
                p.exemptServiceAccounts().forEach(id -> exempt.add(accountNames.getOrDefault(id, id.toString())));
                write(out, "DataPolicy", null, spec);
            }
            return out.toString();
        });
    }

    private ObjectNode meta(String name, String group) {
        ObjectNode m = json.createObjectNode().put("name", name);
        if (group != null) {
            m.put("group", group);
        }
        return m;
    }

    private void write(StringWriter out, String kind, ObjectNode metadata, ObjectNode spec) {
        ObjectNode doc = json.createObjectNode().put("apiVersion", API_VERSION).put("kind", kind);
        if (metadata != null) {
            doc.set("metadata", metadata);
        }
        if (spec != null) {
            doc.set("spec", stripNulls(spec));
        }
        try {
            out.write("---\n");
            out.write(yaml.writeValueAsString(doc));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Leaves out nulls and empty containers so the file shows only what was set. */
    private static JsonNode stripNulls(JsonNode node) {
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
