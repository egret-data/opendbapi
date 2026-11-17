package io.opendbapi.core.endpoint;

import io.opendbapi.core.script.ScriptRejected;
import io.opendbapi.core.script.ScriptSandbox;
import io.opendbapi.sql.expr.Expression;
import io.opendbapi.sql.template.SqlTemplate;
import io.opendbapi.sql.template.TemplateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A workflow task compiled once (spec §6.1): its nodes checked and ordered into levels. Nodes
 * of one level depend only on earlier levels, so they may run at the same time.
 */
public record CompiledWorkflow(List<Node> nodes, List<List<Node>> levels, String output) {

    public static final int MAX_NODES = 20;
    private static final Pattern ID = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,40}$");
    private static final Set<String> RESULTS = Set.of("list", "one", "value", "update");

    /** A compiled node: exactly one of {@code sql} and {@code script} is set. */
    public record Node(String id, StatementRenderer sql, ScriptSandbox.Compiled script, List<String> dependsOn, Expression when,
                       String result) {
    }

    static CompiledWorkflow compile(EndpointDefinition.Task task, ScriptSandbox sandbox) {
        List<EndpointDefinition.WorkflowNode> defs = task.nodes();
        if (defs.isEmpty()) {
            throw new TemplateException("The workflow has no nodes.");
        }
        if (defs.size() > MAX_NODES) {
            throw new TemplateException("A workflow has at most %d nodes.".formatted(MAX_NODES));
        }
        Map<String, Node> byId = new LinkedHashMap<>();
        for (EndpointDefinition.WorkflowNode d : defs) {
            String id = d.id() == null ? "" : d.id().strip();
            if (!ID.matcher(id).matches()) {
                throw new TemplateException("Node ids start with a lower-case letter and use letters, digits and _ (got '%s').".formatted(id));
            }
            if (byId.containsKey(id)) {
                throw new TemplateException("Two nodes are called '%s'.".formatted(id));
            }
            if (!RESULTS.contains(d.result())) {
                throw new TemplateException("Node '%s': result is list, one, value or update.".formatted(id));
            }
            Expression when;
            try {
                when = d.when() == null || d.when().isBlank() ? null : Expression.parse(d.when());
            } catch (RuntimeException e) {
                throw new TemplateException("Node '%s': the when expression does not parse: %s".formatted(id, e.getMessage()));
            }
            Node node = switch (d.kind()) {
                case "sql" -> {
                    if (d.template() == null || d.template().isBlank()) {
                        throw new TemplateException("Node '%s' has no SQL.".formatted(id));
                    }
                    try {
                        yield new Node(id, StatementRenderer.of(SqlTemplate.compile(d.template())), null, d.dependsOn(), when, d.result());
                    } catch (TemplateException e) {
                        throw new TemplateException("Node '%s': %s".formatted(id, e.getMessage()), e);
                    }
                }
                case "script" -> {
                    try {
                        yield new Node(id, null, sandbox.compile(d.source(), ScriptSandbox.ScriptMode.FULL), d.dependsOn(), when, d.result());
                    } catch (ScriptRejected e) {
                        throw new TemplateException("Node '%s', in the script: %s".formatted(id, e.getMessage()));
                    }
                }
                default -> throw new TemplateException("Node '%s': kind is sql or script.".formatted(id));
            };
            byId.put(id, node);
        }
        for (Node n : byId.values()) {
            for (String dep : n.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new TemplateException("Node '%s' depends on '%s', which does not exist.".formatted(n.id(), dep));
                }
                if (dep.equals(n.id())) {
                    throw new TemplateException("Node '%s' depends on itself.".formatted(n.id()));
                }
            }
        }
        List<List<Node>> levels = levels(byId);
        String output = task.output() == null || task.output().isBlank() ? new ArrayList<>(byId.keySet()).get(byId.size() - 1)
                : task.output().strip();
        if (!byId.containsKey(output)) {
            throw new TemplateException("The output node '%s' does not exist.".formatted(output));
        }
        return new CompiledWorkflow(List.copyOf(byId.values()), levels, output);
    }

    /** Kahn's algorithm, keeping the authored order within a level. */
    private static List<List<Node>> levels(Map<String, Node> byId) {
        List<List<Node>> levels = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (done.size() < byId.size()) {
            List<Node> level = new ArrayList<>();
            for (Node n : byId.values()) {
                if (!done.contains(n.id()) && done.containsAll(n.dependsOn())) {
                    level.add(n);
                }
            }
            if (level.isEmpty()) {
                List<String> left = byId.keySet().stream().filter(id -> !done.contains(id)).toList();
                throw new TemplateException("The nodes %s depend on each other in a cycle.".formatted(String.join(", ", left)));
            }
            level.forEach(n -> done.add(n.id()));
            levels.add(List.copyOf(level));
        }
        return List.copyOf(levels);
    }

    /** Request parameters the workflow reads; names of nodes are results, not parameters. */
    Set<String> parameterNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Node n : nodes) {
            out.addAll(n.sql() != null ? n.sql().parameterNames() : n.script().parameterNames());
            if (n.when() != null) {
                out.addAll(n.when().rootVariables());
            }
        }
        nodes.forEach(n -> out.remove(n.id()));
        return out;
    }

    /** Sample context for checks: parameters plus an empty result per node. */
    static Map<String, Object> sampleContext(Map<String, Object> params, CompiledWorkflow w) {
        Map<String, Object> ctx = new HashMap<>(params);
        w.nodes().forEach(n -> ctx.put(n.id(), Map.of()));
        return ctx;
    }
}
