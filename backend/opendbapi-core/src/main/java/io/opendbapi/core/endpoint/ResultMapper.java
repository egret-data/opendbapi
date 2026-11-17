package io.opendbapi.core.endpoint;

import io.opendbapi.core.endpoint.EndpointDefinition.Nest;
import io.opendbapi.core.endpoint.EndpointDefinition.NestCollection;
import io.opendbapi.core.endpoint.EndpointDefinition.Output;
import io.opendbapi.core.error.ApiProblem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shapes result rows for the response (spec §9.2): value types, nesting, key naming, and
 * list/single/scalar. Rows arrive as produced by {@link JdbcValues} (dates as ISO strings,
 * exact numbers).
 */
public final class ResultMapper {

    public static final Set<String> TYPES = Set.of("decimal_string", "iso8601", "epoch_ms", "boolean");
    public static final Set<String> NAMINGS = Set.of("as_is", "camelCase");
    public static final Set<String> SHAPES = Set.of("list", "single", "scalar");

    private ResultMapper() {
    }

    /** The response's {@code data}: a list, one object, or one value. */
    public static Object map(Output out, List<Map<String, Object>> rows) {
        if (out.identity()) {
            return rows;
        }
        List<Map<String, Object>> typed = out.types().isEmpty() ? rows : rows.stream().map(r -> typed(out, r)).toList();
        List<Map<String, Object>> nested = out.nest().isEmpty() ? typed : nest(out.nest().get(0), typed);
        List<Map<String, Object>> named = "camelCase".equals(out.naming()) ? nested.stream().map(ResultMapper::camel).toList() : nested;
        return switch (out.shape()) {
            case "single" -> {
                if (named.isEmpty()) {
                    throw ApiProblem.notFound("not_found", "No matching record.");
                }
                yield named.get(0);
            }
            case "scalar" -> {
                if (named.isEmpty() || named.get(0).isEmpty()) {
                    throw ApiProblem.notFound("not_found", "No matching record.");
                }
                yield named.get(0).values().iterator().next();
            }
            default -> named;
        };
    }

    /** Types and naming for one streamed row; the names are computed once per stream. */
    public static List<String> streamNames(Output out, List<String> columns) {
        return "camelCase".equals(out.naming()) ? columns.stream().map(ResultMapper::camelCase).toList() : columns;
    }

    public static void streamValues(Output out, List<String> columns, Object[] values) {
        if (out.types().isEmpty()) {
            return;
        }
        for (int i = 0; i < values.length && i < columns.size(); i++) {
            String type = out.types().get(columns.get(i));
            if (type != null) {
                values[i] = convert(type, values[i]);
            }
        }
    }

    private static Map<String, Object> typed(Output out, Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        out.types().forEach((column, type) -> {
            if (copy.containsKey(column)) {
                copy.put(column, convert(type, copy.get(column)));
            }
        });
        return copy;
    }

    static Object convert(String type, Object v) {
        if (v == null) {
            return null;
        }
        return switch (type) {
            case "decimal_string" -> v instanceof BigDecimal d ? d.toPlainString()
                    : v instanceof Number n ? new BigDecimal(n.toString()).toPlainString() : v.toString();
            case "boolean" -> bool(v);
            case "epoch_ms" -> epochMillis(v);
            case "iso8601" -> v instanceof Number n ? Instant.ofEpochMilli(n.longValue()).toString() : v.toString();
            default -> v;
        };
    }

    private static Object bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return switch (v.toString().strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "t", "y", "yes" -> true;
            case "0", "false", "f", "n", "no", "" -> false;
            default -> v;
        };
    }

    /** ISO dates and timestamps to milliseconds since the epoch; local times count as UTC. */
    private static Object epochMillis(Object v) {
        if (v instanceof Number) {
            return v;
        }
        String s = v.toString();
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not zoned
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not a timestamp
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return v;
        }
    }

    /** {@code id, name, order_id, order_total} → {@code {id, name, orders: [{id, total}]}}, keeping row order. */
    static List<Map<String, Object>> nest(Nest nest, List<Map<String, Object>> rows) {
        Map<Object, Map<String, Object>> parents = new LinkedHashMap<>();
        Map<Object, List<Set<Object>>> seen = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(nest.key());
            Map<String, Object> parent = parents.get(key);
            if (parent == null) {
                parent = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (nest.collections().stream().noneMatch(c -> e.getKey().startsWith(c.prefix()))) {
                        parent.put(e.getKey(), e.getValue());
                    }
                }
                List<Set<Object>> childKeys = new ArrayList<>();
                for (NestCollection c : nest.collections()) {
                    parent.put(c.as(), new ArrayList<Map<String, Object>>());
                    childKeys.add(new HashSet<>());
                }
                parents.put(key, parent);
                seen.put(key, childKeys);
            }
            for (int i = 0; i < nest.collections().size(); i++) {
                NestCollection c = nest.collections().get(i);
                Object childKey = row.get(c.key());
                if (childKey == null || !seen.get(key).get(i).add(childKey)) {
                    continue;
                }
                Map<String, Object> child = new LinkedHashMap<>();
                row.forEach((column, value) -> {
                    if (column.startsWith(c.prefix())) {
                        child.put(column.substring(c.prefix().length()), value);
                    }
                });
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) parent.get(c.as());
                list.add(child);
            }
        }
        return new ArrayList<>(parents.values());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            Object value = v;
            if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
                value = list.stream().map(m -> camel((Map<String, Object>) m)).toList();
            }
            out.put(camelCase(k), value);
        });
        return out;
    }

    /** {@code created_at} → {@code createdAt}; names without underscores keep their case. */
    public static String camelCase(String name) {
        if (name == null || name.indexOf('_') < 0) {
            return name;
        }
        StringBuilder b = new StringBuilder(name.length());
        boolean upper = false;
        for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '_') {
                upper = b.length() > 0;
            } else {
                b.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return b.toString();
    }

    /** Problems with an output configuration, for the validator. */
    static List<String> problems(Output out, EndpointDefinition def, List<QueryExecutor.ResultColumn> columns) {
        List<String> problems = new ArrayList<>();
        if (!NAMINGS.contains(out.naming())) {
            problems.add("Naming must be as_is or camelCase.");
        }
        if (!SHAPES.contains(out.shape())) {
            problems.add("Shape must be list, single or scalar.");
        }
        if (!"list".equals(out.shape()) && def.pagination().enabled()) {
            problems.add("A %s result cannot be paginated; turn pagination off.".formatted(out.shape()));
        }
        if (!"list".equals(out.shape()) && def.streaming().allowed()) {
            problems.add("A %s result cannot be streamed; turn streaming off.".formatted(out.shape()));
        }
        if (!out.nest().isEmpty() && def.streaming().allowed()) {
            problems.add("Nested results cannot be streamed; turn streaming off or remove nesting.");
        }
        if (!out.nest().isEmpty() && def.pagination().enabled()) {
            problems.add("Nested results cannot be paginated: a page would split a record's children.");
        }
        if (out.nest().size() > 1) {
            problems.add("Nest one level only.");
        }
        Set<String> names = new HashSet<>();
        columns.forEach(c -> names.add(c.name()));
        out.types().forEach((column, type) -> {
            if (!TYPES.contains(type)) {
                problems.add("Type '%s' of '%s' must be decimal_string, iso8601, epoch_ms or boolean.".formatted(type, column));
            } else if (!columns.isEmpty() && !names.contains(column)) {
                problems.add("Type is set for '%s', which the query does not return.".formatted(column));
            }
        });
        for (Nest n : out.nest()) {
            if (n.key() == null || (!columns.isEmpty() && !names.contains(n.key()))) {
                problems.add("Nesting groups by '%s', which the query does not return.".formatted(n.key()));
            }
            for (NestCollection c : n.collections()) {
                if (c.as() == null || c.as().isBlank() || c.prefix() == null || c.prefix().isBlank()) {
                    problems.add("Each nested collection needs a name and a column prefix.");
                } else if (c.key() == null || !c.key().startsWith(c.prefix()) || (!columns.isEmpty() && !names.contains(c.key()))) {
                    problems.add("Collection '%s' needs a key column starting with '%s' that the query returns.".formatted(c.as(), c.prefix()));
                }
            }
        }
        return problems.stream().filter(Objects::nonNull).toList();
    }
}
