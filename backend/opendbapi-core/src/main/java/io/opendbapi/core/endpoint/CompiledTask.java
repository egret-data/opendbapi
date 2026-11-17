package io.opendbapi.core.endpoint;

import io.opendbapi.sql.template.SqlTemplate;
import io.opendbapi.sql.template.TemplateException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A task with its templates compiled once (spec §3.3). */
public record CompiledTask(UUID datasourceId, boolean transaction, List<CompiledStatement> statements, int mainIndex,
                           io.opendbapi.core.script.ScriptSandbox.Compiled full) {

    public CompiledTask(UUID datasourceId, boolean transaction, List<CompiledStatement> statements, int mainIndex) {
        this(datasourceId, transaction, statements, mainIndex, null);
    }

    /** Spec §7.4: a full-mode script, which runs its own queries through db and returns any value. */
    public boolean isFull() {
        return full != null;
    }

    public record CompiledStatement(String id, StatementRenderer template) {
    }

    /** Groovy settings (spec §7.4, §22), set from configuration at start-up. */
    private static volatile io.opendbapi.core.script.ScriptSandbox sandbox = new io.opendbapi.core.script.ScriptSandbox(2000);
    private static volatile io.opendbapi.core.script.ScriptSandbox fullSandbox = new io.opendbapi.core.script.ScriptSandbox(30_000);
    private static volatile boolean scriptsEnabled = true;

    static void configureScripts(boolean enabled, long timeoutMillis, long fullTimeoutMillis) {
        scriptsEnabled = enabled;
        sandbox = new io.opendbapi.core.script.ScriptSandbox(timeoutMillis);
        fullSandbox = new io.opendbapi.core.script.ScriptSandbox(fullTimeoutMillis);
    }

    /** @throws TemplateException naming the statement that does not compile */
    public static CompiledTask compile(EndpointDefinition.Task task) {
        if (task.script()) {
            if (!scriptsEnabled) {
                throw new TemplateException("Groovy scripts are turned off on this server.");
            }
            String mode = task.mode() == null ? "fragment" : task.mode();
            if (!"groovy".equals(task.language() == null ? "groovy" : task.language()) || !java.util.Set.of("fragment", "full").contains(mode)) {
                throw new TemplateException("Scripts are Groovy, in fragment mode (return a sql\"…\" query) or full mode (use db and return any value).");
            }
            if ("full".equals(mode)) {
                try {
                    return new CompiledTask(task.datasourceId(), task.transaction(), List.of(), -1,
                            fullSandbox.compile(task.source(), io.opendbapi.core.script.ScriptSandbox.ScriptMode.FULL));
                } catch (io.opendbapi.core.script.ScriptRejected e) {
                    throw new TemplateException("In the script: " + e.getMessage());
                }
            }
            io.opendbapi.core.script.ScriptSandbox.Compiled compiled;
            try {
                compiled = sandbox.compile(task.source());
            } catch (io.opendbapi.core.script.ScriptRejected e) {
                throw new TemplateException("In the script: " + e.getMessage());
            }
            StatementRenderer renderer = new StatementRenderer() {
                @Override
                public io.opendbapi.sql.template.BoundSql render(java.util.Map<String, Object> params) {
                    return compiled.render(params);
                }

                @Override
                public Set<String> parameterNames() {
                    return compiled.parameterNames();
                }
            };
            return new CompiledTask(task.datasourceId(), task.transaction(), List.of(new CompiledStatement("main", renderer)), 0);
        }
        if (task.statements().isEmpty()) {
            throw new TemplateException("The query has no SQL statement.");
        }
        List<CompiledStatement> compiled = new ArrayList<>();
        int main = task.statements().size() - 1;
        for (int i = 0; i < task.statements().size(); i++) {
            EndpointDefinition.Statement s = task.statements().get(i);
            String label = s.id() == null || s.id().isBlank() ? "statement " + (i + 1) : "statement '" + s.id() + "'";
            try {
                compiled.add(new CompiledStatement(s.id(), StatementRenderer.of(SqlTemplate.compile(s.template()))));
            } catch (TemplateException e) {
                throw new TemplateException("In %s: %s".formatted(label, e.getMessage()), e);
            }
            if ("main".equals(s.id())) {
                main = i;
            }
        }
        return new CompiledTask(task.datasourceId(), task.transaction(), compiled, main);
    }

    public Set<String> parameterNames() {
        if (full != null) {
            return full.parameterNames();
        }
        Set<String> out = new LinkedHashSet<>();
        statements.forEach(s -> out.addAll(s.template().parameterNames()));
        return out;
    }
}
