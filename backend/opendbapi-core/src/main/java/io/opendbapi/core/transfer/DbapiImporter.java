package io.opendbapi.core.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.datasource.JdbcConfig;
import io.opendbapi.core.datasource.JdbcDialect;
import io.opendbapi.core.endpoint.ApiGroupStore;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointDefinition.Param;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamType;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Imports DBAPI's own exports (spec §15.3): {@code api_config.json} (APIs), {@code group.json}
 * and {@code datasource.json}. Groups are matched by name, datasources are recreated from their
 * JDBC URL without passwords, and APIs become drafts. Anything OpenDBAPI does not carry over
 * (plugins, parameter rules, non-SQL executors, {@code ${}} text substitution) is reported, not
 * silently dropped. A dry run reports the same without writing.
 */
@Service
public class DbapiImporter {

    /** The three export files, each as parsed JSON (an array); any may be missing. */
    public record DbapiExport(@Schema(nullable = true) JsonNode apis, @Schema(nullable = true) JsonNode groups,
                              @Schema(nullable = true) JsonNode datasources) {
    }

    /** @param result created, existing (matched by name), or skipped */
    public record ImportedItem(String kind, String name, @Schema(allowableValues = {"created", "existing", "skipped"}) String result,
                               @Schema(nullable = true) UUID id, List<String> notes) {
    }

    public record ImportReport(boolean dryRun, int created, int skipped, List<ImportedItem> items) {
    }

    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_\\-{}]+(/[A-Za-z0-9_\\-{}]+)*$");
    private static final Pattern HOST_PORT_DB = Pattern.compile("^//([^/:;?]+)(?::(\\d+))?(?:/([^?;]*))?.*$");

    private final WorkspaceScope scope;
    private final ApiGroupStore groups;
    private final DatasourceStore datasources;
    private final EndpointStore endpoints;
    private final ObjectMapper json;

    public DbapiImporter(WorkspaceScope scope, ApiGroupStore groups, DatasourceStore datasources, EndpointStore endpoints,
                         ObjectMapper json) {
        this.scope = scope;
        this.groups = groups;
        this.datasources = datasources;
        this.endpoints = endpoints;
        this.json = json;
    }

    /** Runs in one transaction: a failure part-way leaves nothing behind. */
    public ImportReport importExport(UUID workspaceId, DbapiExport export, boolean dryRun, UUID userId) {
        return scope.call(workspaceId, () -> {
            List<ImportedItem> items = new ArrayList<>();
            Map<String, UUID> groupIds = importGroups(workspaceId, export.groups(), dryRun, items);
            Map<String, UUID> datasourceIds = importDatasources(workspaceId, export.datasources(), dryRun, userId, items);
            importApis(workspaceId, export.apis(), groupIds, datasourceIds, dryRun, userId, items);
            int created = (int) items.stream().filter(i -> "created".equals(i.result())).count();
            int skipped = (int) items.stream().filter(i -> "skipped".equals(i.result())).count();
            return new ImportReport(dryRun, created, skipped, items);
        });
    }

    private Map<String, UUID> importGroups(UUID ws, JsonNode list, boolean dryRun, List<ImportedItem> items) {
        Map<String, UUID> byOldId = new HashMap<>();
        Map<String, UUID> byName = new HashMap<>();
        groups.list().forEach(g -> byName.put(g.name().toLowerCase(Locale.ROOT), g.id()));
        for (JsonNode g : array(list)) {
            String name = text(g, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            UUID existing = byName.get(name.toLowerCase(Locale.ROOT));
            UUID id = existing;
            if (existing == null && !dryRun) {
                id = groups.create(ws, name.strip()).id();
                byName.put(name.toLowerCase(Locale.ROOT), id);
            }
            if (text(g, "id") != null && id != null) {
                byOldId.put(text(g, "id"), id);
            }
            items.add(new ImportedItem("group", name, existing != null ? "existing" : "created", id, List.of()));
        }
        return byOldId;
    }

    private Map<String, UUID> importDatasources(UUID ws, JsonNode list, boolean dryRun, UUID userId, List<ImportedItem> items) {
        Map<String, UUID> byOldId = new HashMap<>();
        Map<String, UUID> byName = new HashMap<>();
        datasources.list().forEach(d -> byName.put(d.name().toLowerCase(Locale.ROOT), d.id()));
        for (JsonNode d : array(list)) {
            String name = text(d, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            List<String> notes = new ArrayList<>();
            UUID existing = byName.get(name.toLowerCase(Locale.ROOT));
            if (existing != null) {
                byOldId.put(text(d, "id"), existing);
                items.add(new ImportedItem("datasource", name, "existing", existing, List.of("A datasource with this name exists; APIs use it.")));
                continue;
            }
            JsonNode detail = d.path("detail");
            if (detail.isTextual()) {
                try {
                    detail = json.readTree(detail.asText());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    detail = json.createObjectNode();
                }
            }
            String type = text(d, "type");
            String url = text(detail, "url");
            JdbcConfig config = url == null ? null : parseJdbcUrl(url, text(detail, "username"), notes);
            if (config == null) {
                if (type != null && !"jdbc".equalsIgnoreCase(type)) {
                    notes.add("Only JDBC datasources are imported; this one is " + type + ".");
                } else if (notes.isEmpty()) {
                    notes.add("No JDBC URL in the export.");
                }
                items.add(new ImportedItem("datasource", name, "skipped", null, notes));
                continue;
            }
            notes.add("Imported read-only and without a password: set the password, and switch to read & write if its APIs change data.");
            UUID id = null;
            try {
                JdbcConfig valid = config.validate();
                if (!dryRun) {
                    Datasource created = datasources.create(ws, name.strip(), text(d, "note"), valid, null, true, userId);
                    id = created.id();
                }
            } catch (ApiProblem e) {
                items.add(new ImportedItem("datasource", name, "skipped", null, List.of(e.getMessage())));
                continue;
            }
            byOldId.put(text(d, "id"), id);
            byName.put(name.toLowerCase(Locale.ROOT), id);
            items.add(new ImportedItem("datasource", name, "created", id, notes));
        }
        return byOldId;
    }

    /** Host, port and database from a JDBC URL of a supported database; null otherwise (with a note). */
    static JdbcConfig parseJdbcUrl(String url, String username, List<String> notes) {
        String u = url.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        JdbcDialect dialect;
        String rest;
        if (lower.startsWith("jdbc:mysql:")) {
            dialect = JdbcDialect.MYSQL;
            rest = u.substring("jdbc:mysql:".length());
        } else if (lower.startsWith("jdbc:mariadb:")) {
            dialect = JdbcDialect.MARIADB;
            rest = u.substring("jdbc:mariadb:".length());
        } else if (lower.startsWith("jdbc:postgresql:")) {
            dialect = JdbcDialect.POSTGRESQL;
            rest = u.substring("jdbc:postgresql:".length());
        } else if (lower.startsWith("jdbc:sqlserver:")) {
            dialect = JdbcDialect.SQLSERVER;
            rest = u.substring("jdbc:sqlserver:".length());
        } else if (lower.startsWith("jdbc:oracle:thin:@")) {
            return parseOracle(u.substring("jdbc:oracle:thin:@".length()), username, notes);
        } else {
            notes.add("Database type not supported: " + u.substring(0, Math.min(u.length(), 20)) + "…");
            return null;
        }
        Matcher m = HOST_PORT_DB.matcher(rest);
        if (!m.matches()) {
            notes.add("Could not read host and database from the JDBC URL.");
            return null;
        }
        String database = m.group(3);
        if (dialect == JdbcDialect.SQLSERVER) {
            Matcher db = Pattern.compile("(?i);database(?:Name)?=([^;]+)").matcher(rest);
            database = db.find() ? db.group(1) : null;
        }
        if (database == null || database.isBlank()) {
            notes.add("The JDBC URL names no database.");
            return null;
        }
        if (rest.contains("?") || (dialect == JdbcDialect.SQLSERVER && rest.indexOf(';') >= 0)) {
            notes.add("URL properties were not imported; add the ones you need (only safe ones are allowed).");
        }
        Integer port = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        return new JdbcConfig(dialect, m.group(1), port, database, username == null ? "" : username, Map.of(), new JdbcConfig.Pool(null, null, null));
    }

    private static JdbcConfig parseOracle(String target, String username, List<String> notes) {
        // host:port:SID, host:port/service or //host:port/service
        Matcher m = Pattern.compile("^(?://)?([^:/]+):(\\d+)[:/]([^?]+)$").matcher(target.strip());
        if (!m.matches()) {
            notes.add("Could not read host and service from the Oracle URL (TNS descriptors are not imported).");
            return null;
        }
        return new JdbcConfig(JdbcDialect.ORACLE, m.group(1), Integer.valueOf(m.group(2)), m.group(3), username == null ? "" : username,
                Map.of(), new JdbcConfig.Pool(null, null, null));
    }

    private void importApis(UUID ws, JsonNode list, Map<String, UUID> groupIds, Map<String, UUID> datasourceIds, boolean dryRun,
                            UUID userId, List<ImportedItem> items) {
        for (JsonNode a : array(list)) {
            String name = text(a, "name");
            String label = name == null || name.isBlank() ? String.valueOf(text(a, "path")) : name;
            List<String> notes = new ArrayList<>();
            String path = text(a, "path") == null ? "" : text(a, "path").strip().replaceAll("^/+", "");
            if (!PATH.matcher(path).matches()) {
                items.add(new ImportedItem("endpoint", label, "skipped", null, List.of("Path '%s' is not a valid OpenDBAPI path.".formatted(path))));
                continue;
            }
            JsonNode tasks = jsonOrText(a, "taskJson", "task");
            JsonNode task = tasks.isArray() && !tasks.isEmpty() ? tasks.get(0) : json.createObjectNode();
            int taskType = task.path("taskType").asInt(1);
            if (taskType != 1) {
                items.add(new ImportedItem("endpoint", label, "skipped", null,
                        List.of("Only SQL executors are imported; this API uses " + (taskType == 2 ? "Elasticsearch" : "HTTP") + ".")));
                continue;
            }
            boolean jsonBody = "application/json".equalsIgnoreCase(text(a, "contentType"));
            String method = jsonBody ? "POST" : "GET";
            if (endpoints.routeTaken(method, path)) {
                items.add(new ImportedItem("endpoint", label, "skipped", null, List.of("%s /%s is already used.".formatted(method, path))));
                continue;
            }
            UUID datasourceId = datasourceIds.get(text(task, "datasourceId"));
            if (datasourceId == null && !dryRun) {
                notes.add("Its datasource was not imported; choose one before publishing.");
            }
            List<EndpointDefinition.Statement> statements = new ArrayList<>();
            JsonNode sqlList = task.path("sqlList");
            for (int i = 0; i < sqlList.size(); i++) {
                String sql = sqlList.get(i).path("sqlText").asText("");
                String id = i == sqlList.size() - 1 ? "main" : "sql" + (i + 1);
                statements.add(new EndpointDefinition.Statement(id, sql));
                if (sql.contains("${")) {
                    notes.add("SQL %d uses ${…} text substitution, which OpenDBAPI does not have: rewrite it with #{…} or <orderBy>.".formatted(i + 1));
                }
                if (sqlList.get(i).hasNonNull("transformPlugin") && !sqlList.get(i).path("transformPlugin").asText().isBlank()) {
                    notes.add("SQL %d had a transform plugin; use result mapping (Settings → Output) instead.".formatted(i + 1));
                }
            }
            if (statements.isEmpty()) {
                statements.add(new EndpointDefinition.Statement("main", "select 1 as ok"));
                notes.add("The API had no SQL; a placeholder query was added.");
            }
            if (statements.size() > 1) {
                notes.add("DBAPI returned every statement's result; OpenDBAPI returns the last one only.");
            }
            List<Param> params = new ArrayList<>();
            for (JsonNode p : jsonOrText(a, "paramsJson", "params")) {
                String pname = text(p, "name");
                if (pname == null || pname.isBlank()) {
                    continue;
                }
                params.add(new Param(pname.strip(), jsonBody ? ParamIn.body : ParamIn.query, paramType(text(p, "type")), false, null, null,
                        null, null, null, null, text(p, "note")));
            }
            if (jsonBody && params.isEmpty() && !"{}".equals(String.valueOf(text(a, "jsonParam")).strip())) {
                notes.add("The API read a JSON body; declare its fields as body parameters.");
            }
            if (!array(a.path("paramRules")).isEmpty()) {
                notes.add("Parameter rules were not imported; write them again under Settings → Parameter rules.");
            }
            if (a.hasNonNull("cachePlugin") && a.path("cachePlugin").hasNonNull("pluginName")) {
                notes.add("The cache plugin was not imported; turn on the result cache under Settings → Cache.");
            }
            if (!array(a.path("alarmPlugins")).isEmpty() && array(a.path("alarmPlugins")).stream().anyMatch(p -> p.hasNonNull("pluginName"))) {
                notes.add("Alarm plugins were not imported; use Settings → Alerts.");
            }
            if (a.hasNonNull("transformScript") || (a.hasNonNull("globalTransformPlugin") && a.path("globalTransformPlugin").hasNonNull("pluginName"))) {
                notes.add("Transform scripts and plugins were not imported; use result mapping or a Groovy script.");
            }
            EndpointDefinition definition = new EndpointDefinition(params,
                    new EndpointDefinition.Task("sql", datasourceId, task.path("transaction").asInt(0) == 1 || task.path("transaction").asBoolean(false),
                            statements), EndpointDefinition.Pagination.off(), EndpointDefinition.Limits.defaults());
            String access = a.path("access").asInt(0) == 1 ? "public" : "private";
            UUID id = null;
            if (!dryRun) {
                Endpoint created = endpoints.create(ws, groupIds.get(text(a, "groupId")), label.strip(), text(a, "note"), method, path, access,
                        definition, userId);
                id = created.id();
            }
            notes.add(0, "Imported as a draft (%s /%s, %s).".formatted(method, path, access));
            items.add(new ImportedItem("endpoint", label, "created", id, notes));
        }
    }

    static ParamType paramType(String dbapi) {
        if (dbapi == null) {
            return ParamType.string;
        }
        return switch (dbapi.strip().toLowerCase(Locale.ROOT)) {
            case "bigint" -> ParamType.bigint;
            case "double" -> ParamType.double_;
            case "date" -> ParamType.datetime;
            case "array<string>" -> ParamType.array_string;
            case "array<bigint>" -> ParamType.array_bigint;
            case "array<double>" -> ParamType.array_double;
            case "array<date>" -> ParamType.array_date;
            default -> ParamType.string;
        };
    }

    /** A field that is either already JSON or JSON written as text (DBAPI stores both forms). */
    private JsonNode jsonOrText(JsonNode node, String jsonField, String textField) {
        if (node.path(jsonField).isArray()) {
            return node.path(jsonField);
        }
        JsonNode t = node.path(textField);
        if (t.isArray()) {
            return t;
        }
        if (t.isTextual() && !t.asText().isBlank()) {
            try {
                return json.readTree(t.asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return json.createArrayNode();
            }
        }
        return json.createArrayNode();
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        } else {
            throw ApiProblem.badRequest("invalid_export", "Each export file must hold a JSON array.");
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
