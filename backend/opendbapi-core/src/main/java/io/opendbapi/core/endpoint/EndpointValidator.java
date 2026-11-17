package io.opendbapi.core.endpoint;

import io.opendbapi.core.datasource.DataSourceRegistry;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.endpoint.EndpointDefinition.Param;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.sql.inspect.SqlInspector;
import io.opendbapi.sql.template.BoundSql;
import io.opendbapi.sql.template.TemplateException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What publish checks (spec §5.1, §7.5): templates compile, parameters line up with the SQL
 * and the path, the SQL passes static inspection, and the result columns are known.
 */
@Component
public class EndpointValidator {

    public record Finding(String severity, String code, String message) {
        static Finding error(String code, String message) {
            return new Finding("error", code, message);
        }

        static Finding warning(String code, String message) {
            return new Finding("warning", code, message);
        }
    }

    public record Validation(List<Finding> findings, List<String> detectedParameters, List<QueryExecutor.ResultColumn> outputColumns) {
        public boolean ok() {
            return findings.stream().noneMatch(f -> "error".equals(f.severity()));
        }
    }

    /** Names the platform reads itself (spec §9.3); not declarable as parameters. */
    public static final Set<String> RESERVED = Set.of("page", "size", "format");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$");

    private final DataSourceRegistry registry;

    public EndpointValidator(DataSourceRegistry registry) {
        this.registry = registry;
    }

    /**
     * The data policies that apply to the endpoint being checked (spec §12).
     *
     * @param rowFilterColumn the row filter's column, or null
     * @param maskColumns     columns of the masks that apply, as written in the policies
     */
    public record PolicyContext(String access, String rowFilterColumn, List<String> maskColumns) {
        public static final PolicyContext NONE = new PolicyContext(null, null, List.of());
    }

    public Validation validate(String method, String path, EndpointDefinition def, Datasource datasource) {
        return validate(method, path, def, datasource, PolicyContext.NONE);
    }

    public Validation validate(String method, String path, EndpointDefinition def, Datasource datasource, PolicyContext policies) {
        List<Finding> findings = new ArrayList<>();
        checkParams(method, path, def, findings);

        if (def.task().datasourceId() == null) {
            findings.add(Finding.error("no_datasource", "Choose the datasource this query runs on."));
        } else if (datasource == null) {
            findings.add(Finding.error("unknown_datasource", "The chosen datasource no longer exists in this workspace."));
        }

        CompiledTask task;
        try {
            task = CompiledTask.compile(def.task());
        } catch (TemplateException e) {
            findings.add(Finding.error("template", e.getMessage()));
            return new Validation(findings, List.of(), List.of());
        }

        Set<String> declared = new HashSet<>();
        def.params().forEach(p -> declared.add(p.name()));
        ParamRules.check(def.rules(), declared, findings);
        Set<String> used = new LinkedHashSet<>(task.parameterNames());
        used.removeAll(RESERVED);
        for (String name : used) {
            if (!declared.contains(name) && !"sort".equals(name)) {
                findings.add(Finding.error("undeclared_param",
                        "The SQL reads '%s', which is not a declared parameter. Add it, or fix the name.".formatted(name)));
            }
        }
        for (Param p : def.params()) {
            if (!used.contains(p.name())) {
                findings.add(Finding.warning("unused_param", "Parameter '%s' is not used by the SQL.".formatted(p.name())));
            }
        }

        Map<String, Object> sample = sampleValues(def.params());
        List<QueryExecutor.ResultColumn> output = List.of();
        boolean readOnlyEndpoint = "GET".equals(method);
        boolean readOnlyDatasource = datasource != null && datasource.readOnly();
        for (int i = 0; i < task.statements().size(); i++) {
            BoundSql bound;
            try {
                bound = task.statements().get(i).template().render(sample);
            } catch (TemplateException e) {
                findings.add(Finding.error("template", e.getMessage()));
                continue;
            }
            for (SqlInspector.SqlFinding f : SqlInspector.inspect(bound.sql(), new SqlInspector.Context(readOnlyEndpoint, readOnlyDatasource))) {
                findings.add(new Finding(f.severity().name().toLowerCase(), f.code(), f.message()));
            }
            if (i == task.mainIndex() && !policies.maskColumns().isEmpty()
                    && io.opendbapi.sql.policy.ColumnLineage.trace(bound.sql()).isEmpty()) {
                findings.add(Finding.warning("mask_untraced", "Masks apply by result column name only: the SQL could not be "
                        + "parsed to see which table columns each result column comes from. Alias masked columns with their own name."));
            }
            if (i == task.mainIndex() && datasource != null) {
                output = describe(datasource, bound, findings);
            }
            if (i == task.mainIndex() && policies.rowFilterColumn() != null) {
                checkRowFilter(policies, bound, output, datasource, findings);
            }
        }
        if (def.pagination().enabled() && def.pagination().defaultSize() > def.pagination().maxSize()) {
            findings.add(Finding.error("pagination", "The default page size is larger than the maximum."));
        }
        return new Validation(findings, new ArrayList<>(used), output);
    }

    /** Spec §12.2: private only, the column is returned, and the filtered SQL is accepted by the database. */
    private void checkRowFilter(PolicyContext policies, BoundSql main, List<QueryExecutor.ResultColumn> output, Datasource datasource,
                                List<Finding> findings) {
        String column = policies.rowFilterColumn();
        if ("public".equals(policies.access())) {
            findings.add(Finding.error("row_filter_public",
                    "This endpoint has a row filter, which needs to know the calling client. Make it private."));
        }
        if (!output.isEmpty() && output.stream().noneMatch(c -> c.name().equalsIgnoreCase(column))) {
            findings.add(Finding.error("row_filter_column",
                    "The row filter reads column '%s', which the query does not return.".formatted(column)));
            return;
        }
        BoundSql filtered;
        try {
            filtered = io.opendbapi.sql.policy.RowFilters.apply(main, column, List.of("sample"));
        } catch (io.opendbapi.sql.policy.RowFilters.UnsupportedException | IllegalArgumentException e) {
            findings.add(Finding.error("row_filter_unsupported", "The row filter cannot be applied: " + e.getMessage()));
            return;
        }
        if (datasource != null && !output.isEmpty()) {
            List<Finding> described = new ArrayList<>();
            describe(datasource, filtered, described);
            if (!described.isEmpty()) {
                findings.add(Finding.error("row_filter_unsupported",
                        "The database rejects the query with the row filter added: " + described.get(0).message()));
            }
        }
    }

    private static void checkParams(String method, String path, EndpointDefinition def, List<Finding> findings) {
        Set<String> seen = new HashSet<>();
        Set<String> pathVariables = new LinkedHashSet<>();
        Matcher m = PATH_VARIABLE.matcher(path == null ? "" : path);
        while (m.find()) {
            pathVariables.add(m.group(1));
        }
        for (Param p : def.params()) {
            if (p.name() == null || !NAME.matcher(p.name()).matches()) {
                findings.add(Finding.error("param_name", "'%s' is not a valid parameter name.".formatted(p.name())));
                continue;
            }
            if (!seen.add(p.name())) {
                findings.add(Finding.error("duplicate_param", "Parameter '%s' is declared twice.".formatted(p.name())));
            }
            if (RESERVED.contains(p.name())) {
                findings.add(Finding.error("reserved_param", "'%s' is reserved for pagination and output format.".formatted(p.name())));
            }
            if (p.in() == ParamIn.path && !pathVariables.contains(p.name())) {
                findings.add(Finding.error("path_param", "Path parameter '%s' is not in the path. Add {%s} to it.".formatted(p.name(), p.name())));
            }
            if (p.in() == ParamIn.path && p.type().isArray()) {
                findings.add(Finding.error("path_param", "Path parameter '%s' cannot be a list.".formatted(p.name())));
            }
            if (p.in() == ParamIn.body && ("GET".equals(method) || "DELETE".equals(method))) {
                findings.add(Finding.error("body_param", "%s requests have no body; '%s' cannot be read from it.".formatted(method, p.name())));
            }
            if (p.pattern() != null && !p.pattern().isBlank()) {
                try {
                    Pattern.compile(p.pattern());
                } catch (RuntimeException e) {
                    findings.add(Finding.error("param_pattern", "The pattern of '%s' is not a valid regular expression.".formatted(p.name())));
                }
            }
        }
        for (String variable : pathVariables) {
            boolean declared = def.params().stream().anyMatch(p -> p.in() == ParamIn.path && p.name().equals(variable));
            if (!declared) {
                findings.add(Finding.error("path_param", "The path has {%s}; declare it as a path parameter.".formatted(variable)));
            }
        }
    }

    /** Values that take every {@code <if>} branch, to check the SQL as fully as possible. */
    static Map<String, Object> sampleValues(List<Param> params) {
        Map<String, Object> out = new HashMap<>();
        for (Param p : params) {
            Object one = switch (p.type().element()) {
                case bigint -> 1L;
                case double_ -> 1.0;
                case decimal -> BigDecimal.ONE;
                case boolean_ -> Boolean.TRUE;
                case date -> LocalDate.now();
                case datetime -> LocalDateTime.now();
                default -> p.enumValues() != null && !p.enumValues().isEmpty() ? p.enumValues().get(0) : "sample";
            };
            out.put(p.name(), p.type().isArray() ? List.of(one) : one);
        }
        return out;
    }

    private List<QueryExecutor.ResultColumn> describe(Datasource ds, BoundSql bound, List<Finding> findings) {
        try (Connection c = registry.connection(ds); PreparedStatement ps = c.prepareStatement(bound.sql())) {
            ResultSetMetaData meta = ps.getMetaData();
            if (meta == null) {
                return List.of();
            }
            List<QueryExecutor.ResultColumn> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(new QueryExecutor.ResultColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
            }
            return columns;
        } catch (Exception e) {
            findings.add(Finding.warning("describe_failed",
                    "The database could not describe the result: " + rootMessage(e)));
            return List.of();
        }
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) {
            e = e.getCause();
        }
        return e.getMessage();
    }
}
