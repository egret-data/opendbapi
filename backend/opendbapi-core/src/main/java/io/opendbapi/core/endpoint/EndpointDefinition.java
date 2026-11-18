package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * An endpoint's editable definition (spec §5.2), stored as {@code endpoint.definition} for the
 * draft and frozen in {@code endpoint_version.definition} on publish. P1 subset: SQL tasks,
 * parameters, pagination and limits.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndpointDefinition(List<Param> params, Task task, Pagination pagination, Limits limits, Streaming streaming,
                                 @Schema(description = "Checks on the bound parameters, run before the query (spec §13.1)")
                                 List<Rule> rules,
                                 Alerts alerts,
                                 @Schema(description = "How rows are shaped in the response (spec §9.2)") Output output,
                                 @Schema(description = "Result cache (spec §9.5)") Cache cache) {

    public EndpointDefinition {
        cache = cache == null ? Cache.off() : cache;
        output = output == null ? Output.defaults() : output;
        rules = rules == null ? List.of() : List.copyOf(rules);
        alerts = alerts == null ? Alerts.off() : alerts;
        params = params == null ? List.of() : List.copyOf(params);
        task = task == null ? Task.empty() : task;
        pagination = pagination == null ? Pagination.off() : pagination;
        limits = limits == null ? Limits.defaults() : limits;
        streaming = streaming == null ? Streaming.off() : streaming;
    }

    public EndpointDefinition(List<Param> params, Task task, Pagination pagination, Limits limits) {
        this(params, task, pagination, limits, null, null, null, null, null);
    }

    /**
     * A condition on the parameters in the template expression language, e.g.
     * {@code to == null or to >= from}; when it is false the call gets 400 with {@code message}.
     */
    public record Rule(String test, String message) {
    }

    /**
     * Spec §9.5: keep answers for {@code ttlSeconds}. {@code client} keys entries by the calling
     * service account; {@code shared} serves one entry to every caller.
     */
    public record Cache(boolean enabled, int ttlSeconds, @Schema(allowableValues = {"client", "shared"}) String scope) {
        public static final int MAX_TTL_SECONDS = 86_400;

        public Cache {
            ttlSeconds = ttlSeconds <= 0 ? 60 : Math.min(ttlSeconds, MAX_TTL_SECONDS);
            scope = scope == null || scope.isBlank() ? "client" : scope;
        }

        public static Cache off() {
            return new Cache(false, 60, "client");
        }

        public boolean shared() {
            return "shared".equals(scope);
        }
    }

    /** Spec §13.2: send failures (5xx) of this endpoint to the workspace's alert channels. */
    public record Alerts(boolean enabled) {
        public static Alerts off() {
            return new Alerts(false);
        }
    }

    public static EndpointDefinition starter(UUID datasourceId) {
        return starter(datasourceId, false);
    }

    /** A new draft: {@code select 1 as ok} on a database, {@code GET /} on an HTTP API. */
    public static EndpointDefinition starter(UUID datasourceId, boolean http) {
        Task task = http
                ? new Task("http", datasourceId, false, List.of(), null, null, null, null, null,
                        new HttpCall("GET", "/", List.of(), List.of(), ""))
                : new Task("sql", datasourceId, false, List.of(new Statement("main", "select 1 as ok")));
        return new EndpointDefinition(List.of(), task, Pagination.off(), Limits.defaults(), Streaming.off(),
                List.of(), Alerts.off(), Output.defaults(), Cache.off());
    }

    public enum StreamFormat { ndjson, csv }

    /**
     * Streamed responses (spec §9.6): the caller asks with {@code ?format=} or {@code Accept}.
     * Rows are written as they are read, without pagination, up to {@code limits.streamMaxRows}.
     */
    public record Streaming(boolean allowed, List<StreamFormat> formats) {
        public Streaming {
            formats = formats == null || formats.isEmpty() ? List.of(StreamFormat.ndjson, StreamFormat.csv) : List.copyOf(formats);
        }

        public static Streaming off() {
            return new Streaming(false, null);
        }

        public boolean allows(StreamFormat f) {
            return allowed && formats.contains(f);
        }
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

    /**
     * One step of a workflow (spec §6.1). It runs once the nodes in {@code dependsOn} are done;
     * an entry {@code check:yes} or {@code check:no} ties it to a branch of the decision node
     * {@code check}, and a node on a branch not taken does not run, nor does anything after it.
     * {@code when} that does not hold skips the node but lets later nodes run (its result is null).
     * Its result is available to later nodes under its id, e.g. {@code #{user.id}}.
     */
    public record WorkflowNode(String id,
                               @Schema(allowableValues = {"sql", "script", "api", "decision", "end"}) String kind,
                               @Schema(nullable = true, description = "sql nodes: the SQL template") String template,
                               @Schema(nullable = true, description = "script nodes: a full-mode Groovy script") String source,
                               @Schema(nullable = true, description = "Ids of earlier nodes; id:yes or id:no for a decision's branch")
                               List<String> dependsOn,
                               @Schema(nullable = true, description = "Run only when this expression holds, e.g. user != null") String when,
                               @Schema(nullable = true, allowableValues = {"list", "one", "value", "update"},
                                       description = "sql nodes: what the node's result is; list by default") String result,
                               @Schema(nullable = true, description = "decision nodes: the expression choosing yes or no") String condition,
                               @Schema(nullable = true, description = "api nodes: the endpoint of this workspace to call (its live version)")
                               UUID endpointId,
                               @Schema(nullable = true, description = "api nodes: parameter name → expression, e.g. name → name or id → user.id")
                               Map<String, String> params,
                               @Schema(nullable = true, description = "end nodes: the expression whose value is the answer, e.g. add")
                               String answer,
                               @Schema(nullable = true, description = "Position on the canvas") Integer x,
                               @Schema(nullable = true, description = "Position on the canvas") Integer y) {
        public WorkflowNode {
            kind = kind == null ? "sql" : kind;
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            result = result == null || result.isBlank() ? "list" : result;
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * {@code type = "sql"}: statements run in order on one connection. {@code type = "script"}
     * (spec §7.4): a Groovy {@code source}; {@code mode = "fragment"} returns the main query,
     * {@code mode = "full"} runs queries through {@code db} and returns the answer itself.
     * {@code type = "workflow"}: a graph of SQL and script nodes; independent nodes run in parallel.
     * {@code type = "http"}: one request to an HTTP datasource ({@code request}).
     */
    public record Task(@Schema(allowableValues = {"sql", "script", "workflow", "http"}) String type, @Schema(nullable = true) UUID datasourceId,
                       boolean transaction, List<Statement> statements,
                       @Schema(nullable = true, allowableValues = {"groovy"}) String language,
                       @Schema(nullable = true, allowableValues = {"fragment", "full"}) String mode,
                       @Schema(nullable = true, description = "Script tasks: the Groovy source") String source,
                       @Schema(nullable = true, description = "Workflow tasks: the nodes of the graph (spec §6.1)") List<WorkflowNode> nodes,
                       @Schema(nullable = true, description = "Workflow tasks: the node whose result is the answer; the last node when empty")
                       String output,
                       @Schema(nullable = true, description = "HTTP tasks: the request to the HTTP datasource") HttpCall request) {
        public Task {
            type = type == null ? "sql" : type;
            statements = statements == null ? List.of() : List.copyOf(statements);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        public Task(String type, UUID datasourceId, boolean transaction, List<Statement> statements) {
            this(type, datasourceId, transaction, statements, null, null, null, null, null, null);
        }

        public Task(String type, UUID datasourceId, boolean transaction, List<Statement> statements, String language, String mode,
                    String source) {
            this(type, datasourceId, transaction, statements, language, mode, source, null, null, null);
        }

        public boolean script() {
            return "script".equals(type);
        }

        public boolean workflow() {
            return "workflow".equals(type);
        }

        public boolean http() {
            return "http".equals(type);
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

    /**
     * An HTTP task's request (spec §6). {@code path} is appended to the datasource's base URL;
     * {@code {expression}} placeholders in it, and the values of {@code query} and {@code body},
     * are expressions over the parameters, as in {@code <if test>}. {@code resultPath} is a JSON
     * Pointer to the rows in the response; empty means the whole body.
     */
    public record HttpCall(
            @Schema(allowableValues = {"GET", "POST", "PUT", "PATCH", "DELETE"}) String method,
            String path,
            @Schema(nullable = true, description = "Query string: name and expression; null values are left out") List<HttpField> query,
            @Schema(nullable = true, description = "JSON body fields: name and expression; none sends no body") List<HttpField> body,
            @Schema(nullable = true, description = "JSON Pointer to the rows, e.g. /data/items") String resultPath) {
        public HttpCall {
            method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
            path = path == null ? "" : path.strip();
            query = query == null ? List.of() : List.copyOf(query);
            body = body == null ? List.of() : List.copyOf(body);
            resultPath = resultPath == null ? "" : resultPath.strip();
        }
    }

    public record HttpField(String name, String value) {
    }

    /**
     * @param mode {@code offset} (page and size) or {@code keyset} (after a cursor, spec §9.3)
     * @param keys keyset only: result columns the pages are ordered by, never null and together unique
     */
    public record Pagination(boolean enabled, int defaultSize, int maxSize, boolean count,
                             @Schema(allowableValues = {"offset", "keyset"}, nullable = true) String mode,
                             @Schema(nullable = true) List<KeysetKey> keys) {
        public Pagination {
            defaultSize = defaultSize <= 0 ? 20 : defaultSize;
            maxSize = maxSize <= 0 ? 500 : maxSize;
            mode = mode == null || mode.isBlank() ? "offset" : mode;
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        public Pagination(boolean enabled, int defaultSize, int maxSize, boolean count) {
            this(enabled, defaultSize, maxSize, count, null, null);
        }

        public static Pagination off() {
            return new Pagination(false, 20, 500, true);
        }

        public boolean keyset() {
            return enabled && "keyset".equals(mode);
        }
    }

    public record KeysetKey(String column, boolean descending) {
    }

    /**
     * Result mapping (spec §9.2), applied after masking: {@code types}, then {@code nest}, then
     * {@code naming}, then {@code shape}.
     *
     * @param naming {@code as_is} or {@code camelCase}
     * @param shape  {@code list}, {@code single} (first row; none is 404) or {@code scalar} (first column of the first row)
     * @param types  result column → {@code decimal_string}, {@code iso8601}, {@code epoch_ms} or {@code boolean}
     */
    public record Output(@Schema(allowableValues = {"as_is", "camelCase"}) String naming,
                         @Schema(allowableValues = {"list", "single", "scalar"}) String shape,
                         @Schema(nullable = true) Map<String, String> types,
                         @Schema(nullable = true) List<Nest> nest) {
        public Output {
            naming = naming == null || naming.isBlank() ? "as_is" : naming;
            shape = shape == null || shape.isBlank() ? "list" : shape;
            types = types == null ? Map.of() : Map.copyOf(types);
            nest = nest == null ? List.of() : List.copyOf(nest);
        }

        public static Output defaults() {
            return new Output(null, null, null, null);
        }

        public boolean identity() {
            return "as_is".equals(naming) && "list".equals(shape) && types.isEmpty() && nest.isEmpty();
        }
    }

    /** Groups flat rows by {@code key}; columns starting with a collection's prefix go into its array. */
    public record Nest(String key, List<NestCollection> collections) {
        public Nest {
            collections = collections == null ? List.of() : List.copyOf(collections);
        }
    }

    /** @param key the (prefixed) column identifying one child; rows where it is null add no child */
    public record NestCollection(String as, String prefix, String key) {
    }

    /**
     * @param ratePerMinute calls per minute this endpoint answers across all callers, per
     *     instance (spec §14); null = no endpoint limit
     */
    public record Limits(int timeoutSeconds, int maxRows,
                         @Schema(nullable = true, description = "Calls per minute across all callers; null = unlimited")
                         Integer ratePerMinute,
                         @Schema(nullable = true, description = "Most rows a streamed response returns; null = maxRows")
                         Integer streamMaxRows) {
        public static final int MAX_TIMEOUT_SECONDS = 300;
        public static final int MAX_ROWS = 100_000;
        public static final int MAX_STREAM_ROWS = 10_000_000;

        public Limits {
            timeoutSeconds = timeoutSeconds <= 0 ? 30 : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
            maxRows = maxRows <= 0 ? 10_000 : Math.min(maxRows, MAX_ROWS);
            ratePerMinute = ratePerMinute == null || ratePerMinute <= 0 ? null : Math.min(ratePerMinute, 1_000_000);
            streamMaxRows = streamMaxRows == null || streamMaxRows <= 0 ? null : Math.min(streamMaxRows, MAX_STREAM_ROWS);
        }

        public Limits(int timeoutSeconds, int maxRows) {
            this(timeoutSeconds, maxRows, null, null);
        }

        public static Limits defaults() {
            return new Limits(30, 10_000, null, null);
        }

        public int streamRowLimit() {
            return streamMaxRows == null ? maxRows : streamMaxRows;
        }
    }
}
