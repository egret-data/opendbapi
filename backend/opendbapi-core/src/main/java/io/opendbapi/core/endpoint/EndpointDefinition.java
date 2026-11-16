package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * An endpoint's editable definition (spec §5.2), stored as {@code endpoint.definition} for the
 * draft and frozen in {@code endpoint_version.definition} on publish. P1 subset: SQL tasks,
 * parameters, pagination and limits.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndpointDefinition(List<Param> params, Task task, Pagination pagination, Limits limits) {

    public EndpointDefinition {
        params = params == null ? List.of() : List.copyOf(params);
        task = task == null ? Task.empty() : task;
        pagination = pagination == null ? Pagination.off() : pagination;
        limits = limits == null ? Limits.defaults() : limits;
    }

    public static EndpointDefinition starter(UUID datasourceId) {
        return new EndpointDefinition(List.of(), new Task("sql", datasourceId, false,
                List.of(new Statement("main", "select 1 as ok"))), Pagination.off(), Limits.defaults());
    }

    public enum ParamIn { path, query, header, body }

    public enum ParamType {
        string, bigint, @com.fasterxml.jackson.annotation.JsonProperty("double") double_, decimal, @com.fasterxml.jackson.annotation.JsonProperty("boolean") boolean_, date, datetime,
        @com.fasterxml.jackson.annotation.JsonProperty("array<string>") array_string,
        @com.fasterxml.jackson.annotation.JsonProperty("array<bigint>") array_bigint,
        @com.fasterxml.jackson.annotation.JsonProperty("array<double>") array_double,
        @com.fasterxml.jackson.annotation.JsonProperty("array<date>") array_date;

        public boolean isArray() {
            return name().startsWith("array_");
        }

        public ParamType element() {
            return switch (this) {
                case array_string -> string;
                case array_bigint -> bigint;
                case array_double -> double_;
                case array_date -> date;
                default -> this;
            };
        }
    }

    /** A request parameter and its constraints (spec §5.3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Param(
            String name,
            ParamIn in,
            ParamType type,
            boolean required,
            @Schema(nullable = true) JsonNode defaultValue,
            @Schema(nullable = true) List<String> enumValues,
            @Schema(nullable = true, description = "Numbers: smallest value; text: shortest length") java.math.BigDecimal min,
            @Schema(nullable = true, description = "Numbers: largest value; text: longest length") java.math.BigDecimal max,
            @Schema(nullable = true, description = "Regular expression the value must match") String pattern,
            @Schema(nullable = true) Integer maxItems,
            @Schema(nullable = true) String description) {

        public Param {
            in = in == null ? ParamIn.query : in;
            type = type == null ? ParamType.string : type;
        }
    }

    /** P1: {@code type = "sql"}: statements run in order on one connection. */
    public record Task(String type, @Schema(nullable = true) UUID datasourceId, boolean transaction, List<Statement> statements) {
        public Task {
            type = type == null ? "sql" : type;
            statements = statements == null ? List.of() : List.copyOf(statements);
        }

        static Task empty() {
            return new Task("sql", null, false, List.of());
        }

        /** The statement whose result is returned: the one called "main", else the last. */
        public Statement main() {
            return statements.stream().filter(s -> "main".equals(s.id())).findFirst()
                    .orElse(statements.isEmpty() ? null : statements.get(statements.size() - 1));
        }
    }

    public record Statement(String id, String template) {
    }

    public record Pagination(boolean enabled, int defaultSize, int maxSize, boolean count) {
        public Pagination {
            defaultSize = defaultSize <= 0 ? 20 : defaultSize;
            maxSize = maxSize <= 0 ? 500 : maxSize;
        }

        public static Pagination off() {
            return new Pagination(false, 20, 500, true);
        }
    }

    public record Limits(int timeoutSeconds, int maxRows) {
        public static final int MAX_TIMEOUT_SECONDS = 300;
        public static final int MAX_ROWS = 100_000;

        public Limits {
            timeoutSeconds = timeoutSeconds <= 0 ? 30 : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
            maxRows = maxRows <= 0 ? 10_000 : Math.min(maxRows, MAX_ROWS);
        }

        public static Limits defaults() {
            return new Limits(30, 10_000);
        }
    }
}
