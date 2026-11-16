package io.opendbapi.core.endpoint;

import io.opendbapi.sql.template.SqlTemplate;
import io.opendbapi.sql.template.TemplateException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A task with its templates compiled once (spec §3.3). */
public record CompiledTask(UUID datasourceId, boolean transaction, List<CompiledStatement> statements, int mainIndex) {

    public record CompiledStatement(String id, SqlTemplate template) {
    }

    /** @throws TemplateException naming the statement that does not compile */
    public static CompiledTask compile(EndpointDefinition.Task task) {
        if (task.statements().isEmpty()) {
            throw new TemplateException("The query has no SQL statement.");
        }
        List<CompiledStatement> compiled = new ArrayList<>();
        int main = task.statements().size() - 1;
        for (int i = 0; i < task.statements().size(); i++) {
            EndpointDefinition.Statement s = task.statements().get(i);
            String label = s.id() == null || s.id().isBlank() ? "statement " + (i + 1) : "statement '" + s.id() + "'";
            try {
                compiled.add(new CompiledStatement(s.id(), SqlTemplate.compile(s.template())));
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
        Set<String> out = new LinkedHashSet<>();
        statements.forEach(s -> out.addAll(s.template().parameterNames()));
        return out;
    }
}
