package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.sql.expr.Expression;
import io.opendbapi.sql.template.TemplateException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An HTTP task compiled once (spec §6): the path split into literal text and expressions, the
 * query and body fields parsed, the result pointer checked.
 */
public final class CompiledHttp {

    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    /** Path text outside placeholders: RFC 3986 path characters, no query, fragment or backslash. */
    private static final Pattern PATH_TEXT = Pattern.compile("^[A-Za-z0-9\\-._~!$&'()*+,;=:@/%]*$");
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z0-9_.\\-\\[\\]]{1,100}$");

    /** One request, ready to send: the path and query encoded, the body as JSON values. */
    public record OutgoingRequest(String method, String pathAndQuery, Map<String, Object> body, String described) {
    }

    private record Part(String text, Expression expression) {
    }

    private record Field(String name, Expression value) {
    }

    private final String method;
    private final String pathTemplate;
    private final List<Part> path;
    private final List<Field> query;
    private final List<Field> body;
    private final JsonPointer resultPath;

    private CompiledHttp(String method, String pathTemplate, List<Part> path, List<Field> query, List<Field> body,
                         JsonPointer resultPath) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.path = path;
        this.query = query;
        this.body = body;
        this.resultPath = resultPath;
    }

    /** @throws TemplateException saying what is wrong */
    public static CompiledHttp compile(EndpointDefinition.HttpCall call) {
        if (call == null) {
            throw new TemplateException("The HTTP task has no request.");
        }
        if (!METHODS.contains(call.method())) {
            throw new TemplateException("The request method is GET, POST, PUT, PATCH or DELETE.");
        }
        String p = call.path();
        if (!p.startsWith("/")) {
            throw new TemplateException("The request path starts with /, e.g. /orders/{id}.");
        }
        List<Part> parts = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(p);
        int at = 0;
        while (m.find()) {
            parts.add(new Part(literal(p.substring(at, m.start())), null));
            parts.add(new Part(null, expression(m.group(1), "{" + m.group(1) + "} in the path")));
            at = m.end();
        }
        parts.add(new Part(literal(p.substring(at)), null));
        List<Field> query = fields(call.query(), "Query");
        List<Field> body = fields(call.body(), "Body");
        if (!body.isEmpty() && "GET".equals(call.method())) {
            throw new TemplateException("A GET request has no body; send these values in the query.");
        }
        JsonPointer pointer;
        try {
            pointer = call.resultPath().isEmpty() ? JsonPointer.empty() : JsonPointer.compile(call.resultPath());
        } catch (IllegalArgumentException e) {
            throw new TemplateException("The result path is a JSON Pointer such as /data/items, or empty for the whole response.");
        }
        return new CompiledHttp(call.method(), p, parts, query, body, pointer);
    }

    private static String literal(String text) {
        if (!PATH_TEXT.matcher(text).matches()) {
            throw new TemplateException("The request path may not contain ?, # or spaces; put query values under Query.");
        }
        for (String segment : text.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new TemplateException("The request path may not contain . or .. segments.");
            }
        }
        return text;
    }

    private static Expression expression(String source, String where) {
        try {
            return Expression.parse(source.strip());
        } catch (RuntimeException e) {
            throw new TemplateException("In %s: %s".formatted(where, e.getMessage()));
        }
    }

    private static List<Field> fields(List<EndpointDefinition.HttpField> in, String label) {
        List<Field> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EndpointDefinition.HttpField f : in) {
            if (f == null || f.name() == null || !FIELD_NAME.matcher(f.name()).matches()) {
                throw new TemplateException("%s field names are letters, digits and _ . - [ ] (max 100).".formatted(label));
            }
            if (!seen.add(f.name()) && "Body".equals(label)) {
                throw new TemplateException("Body field '%s' is set twice.".formatted(f.name()));
            }
            if (f.value() == null || f.value().isBlank()) {
                throw new TemplateException("%s field '%s' needs a value, e.g. the name of a parameter.".formatted(label, f.name()));
            }
            out.add(new Field(f.name(), expression(f.value(), "%s field '%s'".formatted(label.toLowerCase(), f.name()))));
        }
        return out;
    }

    public String method() {
        return method;
    }

    /** Whether the request may change data upstream: anything but GET. */
    public boolean writes() {
        return !"GET".equals(method);
    }

    /** Parameters the expressions read. */
    public Set<String> parameterNames() {
        Set<String> out = new LinkedHashSet<>();
        path.stream().filter(p -> p.expression() != null).forEach(p -> out.addAll(p.expression().rootVariables()));
        query.forEach(f -> out.addAll(f.value().rootVariables()));
        body.forEach(f -> out.addAll(f.value().rootVariables()));
        return out;
    }

    /** @throws ApiProblem 400 {@code invalid_param} when a path value is missing */
    public OutgoingRequest render(Map<String, Object> params) {
        java.util.function.Function<String, Object> vars = params::get;
        StringBuilder url = new StringBuilder();
        for (Part part : path) {
            if (part.text() != null) {
                url.append(part.text());
                continue;
            }
            Object value = part.expression().evaluate(vars);
            if (value == null || value.toString().isEmpty()) {
                throw ApiProblem.badRequest("invalid_param",
                        "The request path needs a value for {%s}.".formatted(part.expression().source()));
            }
            String segment = encode(text(value));
            // "." and ".." would move up the upstream's path once the URL is normalised.
            url.append(segment.equals(".") || segment.equals("..") ? segment.replace(".", "%2E") : segment);
        }
        String sep = "?";
        for (Field f : query) {
            Object value = f.value().evaluate(vars);
            Collection<?> values = value instanceof Collection<?> c ? c : value == null ? List.of() : List.of(value);
            for (Object v : values) {
                if (v != null) {
                    url.append(sep).append(encode(f.name())).append('=').append(encode(text(v)));
                    sep = "&";
                }
            }
        }
        Map<String, Object> json = null;
        if (!body.isEmpty()) {
            json = new LinkedHashMap<>();
            for (Field f : body) {
                json.put(f.name(), f.value().evaluate(vars));
            }
        }
        return new OutgoingRequest(method, url.toString(), json, method + " " + pathTemplate);
    }

    /**
     * Rows from a JSON response: the node at the result path; an array gives one row per element
     * (objects as columns, anything else as {@code value}), an object one row, a scalar one row.
     *
     * @throws ApiProblem 502 {@code upstream_failed} when the pointer finds nothing
     */
    public List<Map<String, Object>> rows(JsonNode response) {
        JsonNode at = response.at(resultPath);
        if (at.isMissingNode()) {
            throw new ApiProblem(502, "upstream_failed", "The upstream response has nothing at %s.".formatted(resultPath));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (at.isArray()) {
            at.forEach(el -> rows.add(row(el)));
        } else if (!at.isNull()) {
            rows.add(row(at));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(JsonNode node) {
        if (node.isObject()) {
            return io.opendbapi.core.endpoint.RequestJson.MAPPER.convertValue(node, LinkedHashMap.class);
        }
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("value", io.opendbapi.core.endpoint.RequestJson.MAPPER.convertValue(node, Object.class));
        return one;
    }

    private static String text(Object value) {
        return value instanceof java.time.temporal.TemporalAccessor || value instanceof Number || value instanceof Boolean
                ? value.toString() : String.valueOf(value);
    }

    /** Percent-encodes everything but RFC 3986 unreserved characters, so a value cannot add / ? # or &. */
    static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
