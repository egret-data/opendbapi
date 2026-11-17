package io.opendbapi.core.endpoint;

import io.opendbapi.sql.template.BoundSql;
import io.opendbapi.sql.template.SqlTemplate;
import java.util.Map;
import java.util.Set;

/** Turns bound parameters into one SQL statement: a compiled template or a fragment script. */
public interface StatementRenderer {

    /**
     * @throws io.opendbapi.sql.template.TemplateException for a template problem
     * @throws io.opendbapi.core.script.ScriptRejected when a script fails
     */
    BoundSql render(Map<String, Object> params);

    /** Parameters the statement reads. */
    Set<String> parameterNames();

    static StatementRenderer of(SqlTemplate template) {
        return new StatementRenderer() {
            @Override
            public BoundSql render(Map<String, Object> params) {
                return template.render(params);
            }

            @Override
            public Set<String> parameterNames() {
                return template.parameterNames();
            }
        };
    }
}
