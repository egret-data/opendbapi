package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.endpoint.EndpointDefinition.Param;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamType;
import io.opendbapi.core.error.ApiProblem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request → typed parameter values (spec §5.3). Every failure is a 400 {@code invalid_param}
 * that names the parameter; a query parameter the endpoint does not declare is refused rather
 * than ignored, so a typo does not silently return unfiltered data.
 */
public final class ParamBinder {

    /** Raw request inputs. Header names are lower-cased by the caller. */
    public record Request(Map<String, String> path, Map<String, List<String>> query, Map<String, String> headers, JsonNode body) {
        public Request {
            path = path == null ? Map.of() : path;
            query = query == null ? Map.of() : query;
            headers = headers == null ? Map.of() : headers;
        }
    }

    private ParamBinder() {
    }

    /** @param reserved query names handled by the platform (page, size, sort, format) */
    public static Map<String, Object> bind(List<Param> params, Request request, Set<String> reserved) {
        Set<String> declaredQuery = new HashSet<>();
        params.stream().filter(p -> p.in() == ParamIn.query).forEach(p -> {
            declaredQuery.add(p.name());
            declaredQuery.add(p.name() + "[]");
        });
        for (String name : request.query().keySet()) {
            if (!declaredQuery.contains(name) && !reserved.contains(name)) {
                throw invalid("Unknown parameter %s. This endpoint accepts: %s.".formatted(name,
                        declaredQuery.stream().filter(n -> !n.endsWith("[]")).sorted().reduce((a, b) -> a + ", " + b).orElse("none")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param p : params) {
            Object value = coerce(p, raw(p, request));
            if (value == null && p.defaultValue() != null && !p.defaultValue().isNull()) {
                value = coerce(p, fromJson(p.defaultValue()));
            }
            if (value == null && p.required()) {
                throw invalid("%s is required.".formatted(p.name()));
            }
            if (value != null) {
                check(p, value);
            }
            out.put(p.name(), value);
        }
        return out;
    }

    /** Raw value: a String, a List of raw values, or null. */
    private static Object raw(Param p, Request r) {
        return switch (p.in()) {
            case path -> r.path().get(p.name());
            case header -> r.headers().get(p.name().toLowerCase(Locale.ROOT));
            case query -> {
                List<String> values = new ArrayList<>(r.query().getOrDefault(p.name(), List.of()));
                values.addAll(r.query().getOrDefault(p.name() + "[]", List.of()));
                if (values.isEmpty()) {
                    yield null;
                }
                if (p.type().isArray()) {
                    List<Object> split = new ArrayList<>();
                    values.forEach(v -> Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(split::add));
                    yield split;
                }
                if (values.size() > 1) {
                    throw invalid("%s was given more than once.".formatted(p.name()));
                }
                yield values.get(0);
            }
            case body -> r.body() == null || !r.body().has(p.name()) ? null : fromJson(r.body().get(p.name()));
        };
    }

    private static Object fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(fromJson(n)));
            return out;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject()) {
            throw invalid("Objects are not accepted as parameter values.");
        }
        return node.asText();
    }

    private static Object coerce(Param p, Object raw) {
        if (raw == null || (raw instanceof String s && s.isEmpty() && p.type() != ParamType.string)) {
            return null;
        }
        if (p.type().isArray()) {
            List<?> items = raw instanceof List<?> l ? l : List.of(raw);
            List<Object> out = new ArrayList<>(items.size());
            for (Object item : items) {
                out.add(scalar(p, p.type().element(), item));
            }
            return out;
        }
        if (raw instanceof List<?>) {
            throw invalid("%s takes a single value, not a list.".formatted(p.name()));
        }
        return scalar(p, p.type(), raw);
    }

    private static Object scalar(Param p, ParamType type, Object raw) {
        String text = raw.toString().strip();
        try {
            return switch (type) {
                case string -> raw.toString();
                case bigint -> {
                    BigDecimal d = new BigDecimal(text);
                    if (d.stripTrailingZeros().scale() > 0) {
                        throw new NumberFormatException();
                    }
                    yield d.longValueExact();
                }
                case double_ -> Double.parseDouble(text);
                case decimal -> new BigDecimal(text);
                case boolean_ -> switch (text.toLowerCase(Locale.ROOT)) {
                    case "true", "1", "yes" -> Boolean.TRUE;
                    case "false", "0", "no" -> Boolean.FALSE;
                    default -> throw new IllegalArgumentException();
                };
                case date -> parseDateTime(text).toLocalDate();
                case datetime -> parseDateTime(text);
                default -> throw new IllegalStateException(type.name());
            };
        } catch (RuntimeException e) {
            throw invalid("%s must be %s, got '%s'.".formatted(p.name(), describe(type), abbreviate(text)));
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text.matches("-?\\d{1,15}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text)), ZoneOffset.UTC);
        }
        if (text.length() == 10) {
            return LocalDate.parse(text).atStartOfDay();
        }
        String iso = text.replace(' ', 'T');
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
    }

    private static void check(Param p, Object value) {
        if (value instanceof List<?> list) {
            if (p.maxItems() != null && list.size() > p.maxItems()) {
                throw invalid("%s accepts at most %d values.".formatted(p.name(), p.maxItems()));
            }
            list.forEach(v -> checkScalar(p, v));
        } else {
            checkScalar(p, value);
        }
    }

    private static void checkScalar(Param p, Object value) {
        if (p.enumValues() != null && !p.enumValues().isEmpty() && !p.enumValues().contains(value.toString())) {
            throw invalid("%s must be one of: %s.".formatted(p.name(), String.join(", ", p.enumValues())));
        }
        if (p.pattern() != null && !p.pattern().isBlank() && !Pattern.compile(p.pattern()).matcher(value.toString()).find()) {
            throw invalid("%s does not match the expected format.".formatted(p.name()));
        }
        BigDecimal n = value instanceof Number num ? new BigDecimal(num.toString()) : null;
        if (value instanceof String s && (p.min() != null || p.max() != null)) {
            n = BigDecimal.valueOf(s.length());
        }
        if (n != null && p.min() != null && n.compareTo(p.min()) < 0) {
            throw invalid("%s must be at least %s%s.".formatted(p.name(), p.min().toPlainString(), value instanceof String ? " characters" : ""));
        }
        if (n != null && p.max() != null && n.compareTo(p.max()) > 0) {
            throw invalid("%s must be at most %s%s.".formatted(p.name(), p.max().toPlainString(), value instanceof String ? " characters" : ""));
        }
    }

    private static String describe(ParamType type) {
        return switch (type) {
            case bigint -> "a whole number";
            case double_, decimal -> "a number";
            case boolean_ -> "true or false";
            case date -> "a date (YYYY-MM-DD)";
            case datetime -> "a date-time (ISO 8601 or epoch milliseconds)";
            default -> "text";
        };
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static ApiProblem invalid(String detail) {
        return ApiProblem.badRequest("invalid_param", detail);
    }

    /** A request made of plain values by parameter name, as preview and test cases give them. */
    public static Request fromValues(List<EndpointDefinition.Param> params, Map<String, Object> raw) {
        Map<String, String> path = new java.util.HashMap<>();
        Map<String, List<String>> query = new java.util.HashMap<>();
        Map<String, String> headers = new java.util.HashMap<>();
        com.fasterxml.jackson.databind.node.ObjectNode body = RequestJson.MAPPER.createObjectNode();
        for (EndpointDefinition.Param p : params) {
            Object v = raw == null ? null : raw.get(p.name());
            if (v == null || (v instanceof String s && s.isEmpty())) {
                continue;
            }
            switch (p.in()) {
                case path -> path.put(p.name(), v.toString());
                case header -> headers.put(p.name().toLowerCase(), v.toString());
                case query -> query.put(p.name(), v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(v.toString()));
                case body -> body.set(p.name(), RequestJson.MAPPER.valueToTree(v));
            }
        }
        return new Request(path, query, headers, body);
    }
}
