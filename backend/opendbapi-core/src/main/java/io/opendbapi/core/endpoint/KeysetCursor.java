package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.opendbapi.core.error.ApiProblem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The opaque {@code after} cursor of keyset pagination (spec §9.3): the last row's key values
 * with the database type of each, so they bind back as the same type (a timestamp as a
 * timestamp, not as text). It only says where to resume; it grants nothing.
 */
public final class KeysetCursor {

    private KeysetCursor() {
    }

    /** The cursor after {@code row}, or null when a key is missing or null. */
    public static String after(List<EndpointDefinition.KeysetKey> keys, List<QueryExecutor.ResultColumn> columns,
                               Map<String, Object> row) {
        ArrayNode out = RequestJson.MAPPER.createArrayNode();
        for (EndpointDefinition.KeysetKey k : keys) {
            Object value = row.get(k.column());
            if (value == null) {
                return null;
            }
            String type = columns.stream().filter(c -> c.name().equalsIgnoreCase(k.column())).map(QueryExecutor.ResultColumn::type)
                    .findFirst().orElse("");
            out.addArray().add(kind(type)).add(String.valueOf(value));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ApiProblem 400 {@code invalid_cursor} for anything this server did not hand out */
    public static List<Object> decode(String cursor, int keyCount) {
        try {
            JsonNode node = RequestJson.MAPPER.readTree(Base64.getUrlDecoder().decode(cursor.strip()));
            if (!node.isArray() || node.size() != keyCount) {
                throw invalid();
            }
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(value(item.path(0).asText(), item.path(1).asText()));
            }
            return values;
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    /** A small set of kinds that bind portably through JDBC. */
    static String kind(String dbType) {
        String t = dbType.toLowerCase(Locale.ROOT);
        if (t.contains("timestamptz") || t.contains("with time zone") || t.contains("datetimeoffset")) {
            return "tstz";
        }
        if (t.contains("timestamp") || t.contains("datetime")) {
            return "ts";
        }
        if (t.equals("date")) {
            return "date";
        }
        if (t.startsWith("time")) {
            return "time";
        }
        if (t.contains("int") || t.equals("serial") || t.equals("bigserial")) {
            return "int";
        }
        if (t.contains("numeric") || t.contains("decimal") || t.contains("number") || t.contains("float") || t.contains("double")
                || t.contains("real") || t.contains("money")) {
            return "dec";
        }
        if (t.contains("bool") || t.equals("bit")) {
            return "bool";
        }
        return "str";
    }

    private static Object value(String kind, String text) {
        return switch (kind) {
            case "tstz" -> OffsetDateTime.parse(text);
            case "ts" -> LocalDateTime.parse(text);
            case "date" -> LocalDate.parse(text);
            case "time" -> LocalTime.parse(text);
            case "int" -> Long.parseLong(text);
            case "dec" -> new BigDecimal(text);
            case "bool" -> Boolean.parseBoolean(text);
            case "str" -> text;
            default -> throw invalid();
        };
    }

    private static ApiProblem invalid() {
        return ApiProblem.badRequest("invalid_cursor", "The after cursor is not valid; use the meta.nextCursor of a previous page.");
    }
}
