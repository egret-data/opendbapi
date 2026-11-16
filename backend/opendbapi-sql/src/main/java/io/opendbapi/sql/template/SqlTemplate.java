package io.opendbapi.sql.template;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compiled SQL template (spec §7.2). Compile once when an endpoint is validated or
 * published; render per request. Thread-safe and immutable.
 *
 * <pre>
 *   select id, name from users
 *   &lt;where&gt;
 *     &lt;if test="name != null and name != ''"&gt;and name like #{name}&lt;/if&gt;
 *     &lt;if test="ids != null"&gt;and id in (#{ids})&lt;/if&gt;
 *   &lt;/where&gt;
 *   &lt;orderBy param="sort" allow="id, name, created_at" default="id"/&gt;
 * </pre>
 *
 * Every request value reaches the database as a bind parameter; the only request text that
 * becomes SQL is an {@code <orderBy>} column from its whitelist.
 */
public final class SqlTemplate {

    private final String source;
    private final List<SqlNode> nodes;

    private SqlTemplate(String source, List<SqlNode> nodes) {
        this.source = source;
        this.nodes = List.copyOf(nodes);
    }

    /** @throws TemplateException with line information when the template is invalid */
    public static SqlTemplate compile(String source) {
        if (source == null || source.isBlank()) {
            throw new TemplateException("The SQL template is empty.");
        }
        return new SqlTemplate(source, new TemplateCompiler(source).compile());
    }

    /** @throws TemplateException when a value does not fit (e.g. a non-whitelisted sort column) */
    public BoundSql render(Map<String, ?> parameters) {
        Scope scope = new Scope(name -> parameters.get(name));
        SqlBuilder out = new SqlBuilder();
        SqlNode.renderAll(nodes, scope, out);
        return new BoundSql(out.sql().strip(), out.parameters());
    }

    /** Request parameters the template reads, for "detect parameters" and validation. */
    public Set<String> parameterNames() {
        Set<String> out = new LinkedHashSet<>();
        SqlNode.collectAll(nodes, new HashSet<>(), out);
        return out;
    }

    public String source() {
        return source;
    }
}
