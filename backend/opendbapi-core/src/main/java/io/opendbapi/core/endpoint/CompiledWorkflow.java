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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A workflow task compiled once (spec §6.1): its nodes checked and ordered into levels. Nodes
 * of one level depend only on earlier levels, so they may run at the same time.
 *
 * @param output the node whose result is the answer; null means the last sink that ran
 */
public record CompiledWorkflow(List<Node> nodes, List<List<Node>> levels, String output) {

    public static final int MAX_NODES = 20;
    private static final Pattern ID = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,40}$");
    private static final Set<String> RESULTS = Set.of("list", "one", "value", "update");

    public enum Kind { SQL, SCRIPT, API, DECISION, END }

    /** A dependency; {@code branch} is true for {@code :yes}, false for {@code :no}, null for none. */
    public record Dep(String id, Boolean branch) {
    }

    /** A compiled node; only the fields of its kind are set. */
    public record Node(String id, Kind kind, StatementRenderer sql, ScriptSandbox.Compiled script, List<Dep> dependsOn,
                       Expression when, String result, Expression condition, UUID endpointId, Map<String, Expression> params,
                       Expression answer, boolean sink) {
    }

    static CompiledWorkflow compile(EndpointDefinition.Task task, ScriptSandbox sandbox) {
        List<EndpointDefinition.WorkflowNode> defs = task.nodes();
        if (defs.isEmpty()) {
            throw new TemplateException("The workflow has no nodes.");
        }
        if (defs.size() > MAX_NODES) {
            throw new TemplateException("A workflow has at most %d nodes.".formatted(MAX_NODES));
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> decisions = new HashSet<>();
        Set<String> ends = new HashSet<>();
        for (EndpointDefinition.WorkflowNode d : defs) {
            String id = d.id() == null ? "" : d.id().strip();
            if (!ID.matcher(id).matches()) {
                throw new TemplateException("Node ids start with a lower-case letter and use letters, digits and _ (got '%s').".formatted(id));
            }
            if (!ids.add(id)) {
                throw new TemplateException("Two nodes are called '%s'.".formatted(id));
            }
            if ("decision".equals(d.kind())) {
                decisions.add(id);
            }
            if ("end".equals(d.kind())) {
                ends.add(id);
            }
        }
        Set<String> needed = new HashSet<>();
        Map<String, Node> byId = new LinkedHashMap<>();
        for (EndpointDefinition.WorkflowNode d : defs) {
            String id = d.id().strip();
            List<Dep> deps = new ArrayList<>();
            for (String raw : d.dependsOn()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String[] parts = raw.strip().split(":", 2);
                String target = parts[0];
                Boolean branch = null;
                if (parts.length == 2) {
                    if (!parts[1].equals("yes") && !parts[1].equals("no")) {
                        throw new TemplateException("Node '%s': a branch is written id:yes or id:no (got '%s').".formatted(id, raw));
                    }
                    if (!decisions.contains(target)) {
                        throw new TemplateException("Node '%s' follows a branch of '%s', which is not a decision node.".formatted(id, target));
                    }
                    branch = parts[1].equals("yes");
                }
                if (!ids.contains(target)) {
                    throw new TemplateException("Node '%s' depends on '%s', which does not exist.".formatted(id, target));
                }
                if (target.equals(id)) {
                    throw new TemplateException("Node '%s' depends on itself.".formatted(id));
                }
                if (ends.contains(target)) {
                    throw new TemplateException("Node '%s' comes after the end node '%s'; end nodes finish a branch.".formatted(id, target));
                }
                deps.add(new Dep(target, branch));
                needed.add(target);
            }
            Expression when = expression(id, "when", d.when(), false);
            String kind = d.kind();
            Node node = switch (kind) {
                case "sql" -> {
                    if (!RESULTS.contains(d.result())) {
                        throw new TemplateException("Node '%s': result is list, one, value or update.".formatted(id));
                    }
                    if (d.template() == null || d.template().isBlank()) {
                        throw new TemplateException("Node '%s' has no SQL.".formatted(id));
                    }
                    try {
                        yield new Node(id, Kind.SQL, StatementRenderer.of(SqlTemplate.compile(d.template())), null, deps, when, d.result(),
                                null, null, Map.of(), null, false);
                    } catch (TemplateException e) {
                        throw new TemplateException("Node '%s': %s".formatted(id, e.getMessage()), e);
                    }
                }
                case "script" -> {
                    try {
                        yield new Node(id, Kind.SCRIPT, null, sandbox.compile(d.source(), ScriptSandbox.ScriptMode.FULL), deps, when,
                                d.result(), null, null, Map.of(), null, false);
                    } catch (ScriptRejected e) {
                        throw new TemplateException("Node '%s', in the script: %s".formatted(id, e.getMessage()));
                    }
                }
                case "api" -> {
                    if (d.endpointId() == null) {
                        throw new TemplateException("Node '%s': choose the endpoint to call.".formatted(id));
                    }
                    Map<String, Expression> params = new LinkedHashMap<>();
                    d.params().forEach((name, value) -> {
                        if (value != null && !value.isBlank()) {
                            params.put(name, expression(id, "parameter " + name, value, true));
                        }
                    });
                    yield new Node(id, Kind.API, null, null, deps, when, d.result(), null, d.endpointId(), Map.copyOf(params), null, false);
                }
                case "decision" -> new Node(id, Kind.DECISION, null, null, deps, when, d.result(),
                        expression(id, "condition", d.condition(), true), null, Map.of(), null, false);
                case "end" -> new Node(id, Kind.END, null, null, deps, when, d.result(), null, null, Map.of(),
                        expression(id, "answer", d.answer(), true), false);
                default -> throw new TemplateException("Node '%s': kind is sql, script, api, decision or end.".formatted(id));
            };
            byId.put(id, node);
        }
        Map<String, Node> withSinks = new LinkedHashMap<>();
        byId.forEach((id, n) -> withSinks.put(id, needed.contains(id) ? n : new Node(n.id(), n.kind(), n.sql(), n.script(), n.dependsOn(),
                n.when(), n.result(), n.condition(), n.endpointId(), n.params(), n.answer(), true)));
        List<List<Node>> levels = levels(withSinks);
        String output = task.output() == null || task.output().isBlank() ? null : task.output().strip();
        if (output != null && !withSinks.containsKey(output)) {
            throw new TemplateException("The output node '%s' does not exist.".formatted(output));
        }
        return new CompiledWorkflow(List.copyOf(withSinks.values()), levels, output);
    }

    private static Expression expression(String node, String what, String source, boolean required) {
        if (source == null || source.isBlank()) {
            if (required) {
                throw new TemplateException("Node '%s': give the %s.".formatted(node, what));
            }
            return null;
        }
        try {
            return Expression.parse(source);
        } catch (RuntimeException e) {
            throw new TemplateException("Node '%s': the %s does not parse: %s".formatted(node, what, e.getMessage()));
        }
    }

    /** Kahn's algorithm, keeping the authored order within a level. */
    private static List<List<Node>> levels(Map<String, Node> byId) {
        List<List<Node>> levels = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (done.size() < byId.size()) {
            List<Node> level = new ArrayList<>();
            for (Node n : byId.values()) {
                if (!done.contains(n.id()) && n.dependsOn().stream().allMatch(d -> done.contains(d.id()))) {
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
            if (n.sql() != null) {
                out.addAll(n.sql().parameterNames());
            }
            if (n.script() != null) {
                out.addAll(n.script().parameterNames());
            }
            for (Expression e : expressions(n)) {
                out.addAll(e.rootVariables());
            }
        }
        nodes.forEach(n -> out.remove(n.id()));
        return out;
    }

    private static List<Expression> expressions(Node n) {
        List<Expression> out = new ArrayList<>(n.params().values());
        if (n.when() != null) {
            out.add(n.when());
        }
        if (n.condition() != null) {
            out.add(n.condition());
        }
        if (n.answer() != null) {
            out.add(n.answer());
        }
        return out;
    }

    /** Sample context for checks: parameters plus an empty result per node. */
    static Map<String, Object> sampleContext(Map<String, Object> params, CompiledWorkflow w) {
        Map<String, Object> ctx = new HashMap<>(params);
        w.nodes().forEach(n -> ctx.put(n.id(), Map.of()));
        return ctx;
    }
}
