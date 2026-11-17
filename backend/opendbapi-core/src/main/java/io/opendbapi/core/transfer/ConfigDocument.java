package io.opendbapi.core.transfer;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One YAML document of a workspace configuration (spec §15.1). {@code key} identifies it
 * across environments: the name, or for an endpoint its route ({@code GET /path}), for a grant
 * and a data policy what they connect.
 */
public record ConfigDocument(String kind, String key, ObjectNode metadata, ObjectNode spec) {

    public static final String DATASOURCE = "Datasource";
    public static final String GROUP = "Group";
    public static final String ENDPOINT = "Endpoint";
    public static final String SERVICE_ACCOUNT = "ServiceAccount";
    public static final String GRANT = "Grant";
    public static final String DATA_POLICY = "DataPolicy";

    /** Kinds in the order they are written and applied; deletions run in reverse. */
    public static final java.util.List<String> ORDER = java.util.List.of(DATASOURCE, GROUP, SERVICE_ACCOUNT, ENDPOINT, DATA_POLICY, GRANT);

    public static String grantKey(String serviceAccount, String type, String target) {
        return serviceAccount + " → " + type + " " + target;
    }

    public static String policyKey(String kind, String column, String datasource, String endpoint) {
        return kind + " " + column + " on " + (datasource != null ? "datasource " + datasource : "endpoint " + endpoint);
    }
}
