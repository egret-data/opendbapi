package io.opendbapi.core.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointDefinition.Param;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds an OpenAPI 3.1 document from live endpoint versions (spec §10): parameters from the
 * definition, responses from the output columns frozen at publish, the {@code {data, meta}}
 * envelope, RFC 9457 errors, and OAuth2 client credentials for private endpoints.
 */
public final class OpenApiBuilder {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> INTEGER = Set.of("int2", "int4", "int8", "int", "integer", "smallint", "bigint", "tinyint",
            "mediumint", "serial", "bigserial", "smallserial", "int unsigned", "bigint unsigned", "long");
    private static final Set<String> NUMBER = Set.of("float4", "float8", "real", "double", "double precision", "float", "numeric",
            "decimal", "money", "number", "binary_float", "binary_double", "smallmoney");
    private static final Set<String> BOOLEAN = Set.of("bool", "boolean", "bit");

    /**
     * @param title document title, usually the workspace name
     * @param serverUrl base URL of the workspace's endpoints: {@code https://host/api/{ws}}
     * @param tokenUrl the OAuth2 token endpoint
     */
    public record Target(String title, String serverUrl, String tokenUrl) {
    }

    private OpenApiBuilder() {
    }

    public static ObjectNode build(Target target, List<DocsStore.DocEntry> entries, ObjectMapper json) {
        ObjectNode doc = F.objectNode();
        doc.put("openapi", "3.1.0");
        ObjectNode info = doc.putObject("info");
        info.put("title", target.title());
        info.put("version", "live");
        info.put("description", "Generated from the live version of each endpoint. Private endpoints need an access token "
                + "from the OAuth2 client-credentials flow.");
        doc.putArray("servers").addObject().put("url", target.serverUrl());

        ObjectNode paths = doc.putObject("paths");
        Set<String> tags = new TreeSet<>();
        for (DocsStore.DocEntry e : entries) {
            String path = "/" + e.snapshot().path();
            ObjectNode item = paths.has(path) ? (ObjectNode) paths.get(path) : paths.putObject(path);
            item.set(e.snapshot().method().toLowerCase(Locale.ROOT), operation(e, json));
            if (e.group() != null) {
                tags.add(e.group());
            }
        }
        ArrayNode tagList = doc.putArray("tags");
        tags.forEach(t -> tagList.addObject().put("name", t));

        ObjectNode components = doc.putObject("components");
        ObjectNode oauth = components.putObject("securitySchemes").putObject("oauth2");
        oauth.put("type", "oauth2");
        oauth.put("description", "Client credentials of a service account; ask the workspace admin for a client.");
        ObjectNode cc = oauth.putObject("flows").putObject("clientCredentials");
        cc.put("tokenUrl", target.tokenUrl());
        cc.putObject("scopes");
        ObjectNode schemas = components.putObject("schemas");
        schemas.set("Problem", problemSchema());
        schemas.set("Meta", metaSchema());
        return doc;
    }

    private static ObjectNode operation(DocsStore.DocEntry e, ObjectMapper json) {
        EndpointDefinition def = e.snapshot().definition();
        ObjectNode op = F.objectNode();
        op.put("operationId", operationId(e));
        op.put("summary", e.name());
        if (e.deprecated()) {
            op.put("deprecated", true);
        }
        if (e.description() != null && !e.description().isBlank()) {
            op.put("description", e.description());
        }
        if (e.group() != null) {
            op.putArray("tags").add(e.group());
        }
        op.put("x-opendbapi-version", e.version());

        ArrayNode parameters = op.putArray("parameters");
        ObjectNode bodyProps = F.objectNode();
        ArrayNode bodyRequired = F.arrayNode();
        for (Param p : def.params()) {
            ObjectNode schema = paramSchema(p, json);
            if (p.in() == ParamIn.body) {
                bodyProps.set(p.name(), schema);
                if (p.required()) {
                    bodyRequired.add(p.name());
                }
                continue;
            }
            ObjectNode param = parameters.addObject();
            param.put("name", p.name());
            param.put("in", p.in().name());
            param.put("required", p.in() == ParamIn.path || p.required());
            if (p.description() != null) {
                param.put("description", p.description());
            }
            if (p.type().isArray() && p.in() == ParamIn.query) {
                param.put("style", "form");
                param.put("explode", true);
            }
            param.set("schema", schema);
        }
        if (def.pagination().keyset()) {
            ObjectNode after = parameters.addObject();
            after.put("name", "after");
            after.put("in", "query");
            after.put("required", false);
            after.put("description", "meta.nextCursor of the previous page; omit for the first page");
            after.putObject("schema").put("type", "string");
            parameters.add(intParam("size", "Rows per page", 1, def.pagination().maxSize(), def.pagination().defaultSize()));
        } else if (def.pagination().enabled()) {
            parameters.add(intParam("page", "Page number, from 1", 1, null, 1));
            parameters.add(intParam("size", "Rows per page", 1, def.pagination().maxSize(), def.pagination().defaultSize()));
        }
        if (def.streaming().allowed()) {
            ObjectNode format = parameters.addObject();
            format.put("name", "format");
            format.put("in", "query");
            format.put("required", false);
            format.put("description", "Stream the rows instead of a JSON envelope (no pagination); Accept works too");
            ArrayNode values = format.putObject("schema").put("type", "string").put("default", "json").putArray("enum");
            values.add("json");
            def.streaming().formats().forEach(f -> values.add(f.name()));
        }
        if (!bodyProps.isEmpty()) {
            ObjectNode schema = F.objectNode().put("type", "object");
            schema.set("properties", bodyProps);
            if (!bodyRequired.isEmpty()) {
                schema.set("required", bodyRequired);
            }
            ObjectNode body = op.putObject("requestBody");
            body.put("required", bodyRequired.size() > 0);
            body.putObject("content").putObject("application/json").set("schema", schema);
        }

        ObjectNode responses = op.putObject("responses");
        ObjectNode ok = responses.putObject("200");
        ok.put("description", "OK");
        ObjectNode content = ok.putObject("content");
        content.putObject("application/json").set("schema", envelope(e, def));
        if (def.streaming().allows(EndpointDefinition.StreamFormat.ndjson)) {
            content.putObject("application/x-ndjson").putObject("schema").put("type", "string")
                    .put("description", "One row object per line; a last {\"_meta\":{...}} line when cut short");
        }
        if (def.streaming().allows(EndpointDefinition.StreamFormat.csv)) {
            content.putObject("text/csv").putObject("schema").put("type", "string").put("description", "Header row, then one line per row");
        }
        problem(responses, "400", "A parameter is missing or invalid (code invalid_param)");
        boolean isPrivate = !"public".equals(e.snapshot().access());
        if (isPrivate) {
            problem(responses, "401", "Missing, expired or revoked token");
            problem(responses, "403", "The client has no grant on this endpoint, or the address is blocked");
            op.putArray("security").addObject().putArray("oauth2");
        } else {
            problem(responses, "403", "The caller's address is blocked");
            op.putArray("security").addObject(); // explicitly anonymous
        }
        problem(responses, "404", "Not found");
        problem(responses, "429", "Rate limit reached; see Retry-After");
        problem(responses, "500", "The query failed; quote the requestId to the endpoint's owner");
        problem(responses, "504", "The query took too long");
        return op;
    }

    private static ObjectNode envelope(DocsStore.DocEntry e, EndpointDefinition def) {
        ObjectNode schema = F.objectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        JsonNode columns = e.outputSchema();
        boolean rows = columns != null && columns.isArray() && !columns.isEmpty();
        if (rows || "GET".equals(e.snapshot().method())) {
            props.set("data", dataSchema(def.output(), columns));
        } else {
            ObjectNode data = props.putObject("data").put("type", "object");
            data.putObject("properties").putObject("affectedRows").put("type", "integer");
        }
        props.putObject("meta").put("$ref", "#/components/schemas/Meta");
        schema.putArray("required").add("data").add("meta");
        return schema;
    }

    /** {@code data} after result mapping (spec §9.2): types, nesting, naming and shape. */
    static ObjectNode dataSchema(EndpointDefinition.Output out, JsonNode columns) {
        java.util.List<String[]> cols = new java.util.ArrayList<>();
        if (columns != null && columns.isArray()) {
            columns.forEach(c -> cols.add(new String[]{c.path("name").asText(), c.path("type").asText()}));
        }
        java.util.function.UnaryOperator<String> name = n -> "camelCase".equals(out.naming())
                ? io.opendbapi.core.endpoint.ResultMapper.camelCase(n) : n;
        java.util.function.Function<String[], ObjectNode> schemaOf = c -> {
            String type = out.types().get(c[0]);
            if (type == null) {
                return columnSchema(c[1]);
            }
            ObjectNode s = F.objectNode();
            switch (type) {
                case "decimal_string" -> s.putArray("type").add("string").add("null");
                case "epoch_ms" -> s.putArray("type").add("integer").add("null");
                case "boolean" -> s.putArray("type").add("boolean").add("null");
                default -> s.put("format", "date-time").putArray("type").add("string").add("null");
            }
            return s;
        };
        ObjectNode row = F.objectNode().put("type", "object");
        ObjectNode rowProps = row.putObject("properties");
        EndpointDefinition.Nest nest = out.nest().isEmpty() ? null : out.nest().get(0);
        for (String[] c : cols) {
            if (nest == null || nest.collections().stream().noneMatch(n -> c[0].startsWith(n.prefix()))) {
                rowProps.set(name.apply(c[0]), schemaOf.apply(c));
            }
        }
        if (nest != null) {
            for (EndpointDefinition.NestCollection n : nest.collections()) {
                ObjectNode child = F.objectNode().put("type", "object");
                ObjectNode childProps = child.putObject("properties");
                for (String[] c : cols) {
                    if (c[0].startsWith(n.prefix())) {
                        childProps.set(name.apply(c[0].substring(n.prefix().length())), schemaOf.apply(c));
                    }
                }
                rowProps.putObject(n.as()).put("type", "array").set("items", child);
            }
        }
        return switch (out.shape()) {
            case "single" -> row;
            case "scalar" -> cols.isEmpty() ? F.objectNode() : schemaOf.apply(cols.get(0));
            default -> {
                ObjectNode list = F.objectNode().put("type", "array");
                list.set("items", row);
                yield list;
            }
        };
    }

    /** JSON Schema of a result column from its database type name; every column may be null. */
    static ObjectNode columnSchema(String dbType) {
        String t = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT).strip();
        ObjectNode s = F.objectNode();
        if (t.startsWith("_") || t.endsWith("[]") || t.equals("array")) {
            s.putArray("type").add("array").add("null");
            return s;
        }
        String base = t.replaceAll("\\(.*", "").strip();
        String type;
        if (INTEGER.contains(base)) {
            type = "integer";
        } else if (NUMBER.contains(base)) {
            type = "number";
        } else if (BOOLEAN.contains(base)) {
            type = "boolean";
        } else if (base.equals("json") || base.equals("jsonb")) {
            return s; // any JSON value
        } else {
            type = "string";
            if (base.equals("date")) {
                s.put("format", "date");
            } else if (base.startsWith("timestamp") || base.equals("datetime") || base.equals("datetime2")
                    || base.equals("smalldatetime") || base.equals("datetimeoffset")) {
                s.put("format", "date-time");
            } else if (base.startsWith("time")) {
                s.put("format", "time");
            } else if (base.equals("uuid") || base.equals("uniqueidentifier")) {
                s.put("format", "uuid");
            } else if (base.equals("bytea") || base.contains("blob") || base.contains("binary") || base.equals("image")
                    || base.equals("raw")) {
                s.put("contentEncoding", "base64");
            }
        }
        s.putArray("type").add(type).add("null");
        return s;
    }

    private static ObjectNode paramSchema(Param p, ObjectMapper json) {
        ObjectNode s;
        if (p.type().isArray()) {
            s = F.objectNode().put("type", "array");
            s.set("items", scalarSchema(p.type().element()));
            if (p.maxItems() != null) {
                s.put("maxItems", p.maxItems());
            }
        } else {
            s = scalarSchema(p.type());
            boolean text = p.type() == ParamType.string;
            if (p.min() != null) {
                s.put(text ? "minLength" : "minimum", text ? p.min().intValue() : p.min().doubleValue());
            }
            if (p.max() != null) {
                s.put(text ? "maxLength" : "maximum", text ? p.max().intValue() : p.max().doubleValue());
            }
            if (p.pattern() != null) {
                s.put("pattern", p.pattern());
            }
            if (p.enumValues() != null && !p.enumValues().isEmpty()) {
                ArrayNode values = s.putArray("enum");
                p.enumValues().forEach(values::add);
            }
        }
        if (p.defaultValue() != null && !p.defaultValue().isNull()) {
            s.set("default", p.defaultValue());
        }
        return s;
    }

    private static ObjectNode scalarSchema(ParamType t) {
        ObjectNode s = F.objectNode();
        switch (t) {
            case bigint -> s.put("type", "integer").put("format", "int64");
            case double_ -> s.put("type", "number").put("format", "double");
            case decimal -> s.put("type", "number");
            case boolean_ -> s.put("type", "boolean");
            case date -> s.put("type", "string").put("format", "date");
            case datetime -> s.put("type", "string").put("format", "date-time");
            default -> s.put("type", "string");
        }
        return s;
    }

    private static ObjectNode intParam(String name, String description, int min, Integer max, int dflt) {
        ObjectNode p = F.objectNode();
        p.put("name", name);
        p.put("in", "query");
        p.put("required", false);
        p.put("description", description);
        ObjectNode s = p.putObject("schema").put("type", "integer").put("minimum", min).put("default", dflt);
        if (max != null) {
            s.put("maximum", max);
        }
        return p;
    }

    private static void problem(ObjectNode responses, String status, String description) {
        ObjectNode r = responses.putObject(status);
        r.put("description", description);
        r.putObject("content").putObject("application/problem+json").putObject("schema").put("$ref", "#/components/schemas/Problem");
    }

    private static ObjectNode problemSchema() {
        ObjectNode s = F.objectNode().put("type", "object").put("description", "RFC 9457 problem details");
        ObjectNode p = s.putObject("properties");
        p.putObject("type").put("type", "string");
        p.putObject("title").put("type", "string");
        p.putObject("status").put("type", "integer");
        p.putObject("detail").put("type", "string");
        p.putObject("code").put("type", "string").put("description", "Stable machine-readable error code");
        p.putObject("requestId").put("type", "string");
        s.putArray("required").add("status").add("code");
        return s;
    }

    private static ObjectNode metaSchema() {
        ObjectNode s = F.objectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("version").put("type", "integer").put("description", "Endpoint version that answered");
        p.putObject("cached").put("type", "boolean");
        p.putObject("page").put("type", "integer");
        p.putObject("size").put("type", "integer");
        p.putObject("total").put("type", "integer");
        p.putObject("hasNext").put("type", "boolean");
        ObjectNode next = p.putObject("nextCursor");
        next.put("description", "Keyset pages: pass as after= to get the next page; null on the last page");
        next.putArray("type").add("string").add("null");
        p.putObject("truncated").put("type", "boolean").put("description", "More rows existed than the endpoint returns");
        p.putObject("requestId").put("type", "string");
        s.putArray("required").add("version").add("requestId");
        return s;
    }

    private static String operationId(DocsStore.DocEntry e) {
        StringBuilder sb = new StringBuilder(e.snapshot().method().toLowerCase(Locale.ROOT));
        for (String part : e.snapshot().path().split("[/{}\\-_]+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
