package io.opendbapi.sql.template;

import io.opendbapi.sql.expr.Expression;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One piece of a compiled template. */
sealed interface SqlNode {

    void render(Scope scope, SqlBuilder out);

    /**
     * Adds the request parameters this node reads to {@code out}; {@code bound} holds names
     * defined by enclosing {@code <bind>}/{@code <foreach>}, which are not request parameters.
     */
    void collectParameters(Set<String> bound, Set<String> out);

    static void renderAll(List<SqlNode> nodes, Scope scope, SqlBuilder out) {
        for (SqlNode node : nodes) {
            node.render(scope, out);
        }
    }

    static void collectAll(List<SqlNode> nodes, Set<String> bound, Set<String> out) {
        for (SqlNode node : nodes) {
            node.collectParameters(bound, out);
        }
    }

    static void collectExpression(Expression e, Set<String> bound, Set<String> out) {
        for (String name : e.rootVariables()) {
            if (!bound.contains(name)) {
                out.add(name);
            }
        }
    }

    /** Literal SQL text. */
    record Text(String sql) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            out.text(sql);
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
        }
    }

    /** {@code #{expr}}: always a bind parameter; a collection becomes {@code ?, ?, ?}. */
    record Placeholder(Expression expression) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            Object value = expression.evaluate(scope);
            List<Object> items = asList(value);
            if (items == null) {
                out.parameter(value);
                return;
            }
            if (items.isEmpty()) {
                // "x IN ()" is a syntax error everywhere; "x IN (NULL)" is valid and matches nothing.
                out.text("NULL");
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.text(", ");
                }
                out.parameter(items.get(i));
            }
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            collectExpression(expression, bound, out);
        }
    }

    record If(Expression test, List<SqlNode> children) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            if (test.test(scope)) {
                renderAll(children, scope, out);
            }
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            collectExpression(test, bound, out);
            collectAll(children, bound, out);
        }
    }

    record Choose(List<If> whens, List<SqlNode> otherwise) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            for (If when : whens) {
                if (when.test().test(scope)) {
                    renderAll(when.children(), scope, out);
                    return;
                }
            }
            renderAll(otherwise, scope, out);
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            whens.forEach(w -> w.collectParameters(bound, out));
            collectAll(otherwise, bound, out);
        }
    }

    /**
     * {@code <trim>}, and {@code <where>}/{@code <set>} as presets: trims the content, removes
     * one matching prefix/suffix override, then wraps it; renders nothing when empty.
     */
    record Trim(String prefix, String suffix, List<String> prefixOverrides, List<String> suffixOverrides,
                List<SqlNode> children) implements SqlNode {

        static Trim where(List<SqlNode> children) {
            return new Trim("WHERE", "", List.of("AND ", "OR ", "AND\n", "OR\n", "AND\t", "OR\t"), List.of(), children);
        }

        static Trim set(List<SqlNode> children) {
            return new Trim("SET", "", List.of(), List.of(","), children);
        }

        public void render(Scope scope, SqlBuilder out) {
            SqlBuilder inner = new SqlBuilder();
            renderAll(children, scope, inner);
            String body = inner.sql().strip();
            if (body.isEmpty()) {
                return;
            }
            for (String override : prefixOverrides) {
                if (body.regionMatches(true, 0, override, 0, override.length())) {
                    body = body.substring(override.length()).stripLeading();
                    break;
                }
            }
            for (String override : suffixOverrides) {
                if (body.length() >= override.length()
                        && body.regionMatches(true, body.length() - override.length(), override, 0, override.length())) {
                    body = body.substring(0, body.length() - override.length()).stripTrailing();
                    break;
                }
            }
            StringBuilder wrapped = new StringBuilder();
            if (!prefix.isEmpty()) {
                wrapped.append(prefix);
                if (!prefix.endsWith("(") && !prefix.endsWith(" ")) {
                    wrapped.append(' ');
                }
            }
            wrapped.append(body);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(")") && !suffix.startsWith(" ")) {
                    wrapped.append(' ');
                }
                wrapped.append(suffix);
            }
            inner.replaceSql(wrapped.toString());
            out.block(inner);
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            collectAll(children, bound, out);
        }
    }

    record Foreach(Expression collection, String item, String index, String open, String close, String separator,
                   List<SqlNode> children) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            Object value = collection.evaluate(scope);
            if (value == null) {
                return;
            }
            List<Object> items = asList(value);
            if (items == null) {
                throw new TemplateException("<foreach collection=\"%s\"> needs a list, got %s."
                        .formatted(collection.source(), value.getClass().getSimpleName()));
            }
            if (items.isEmpty()) {
                return;
            }
            SqlBuilder inner = new SqlBuilder();
            inner.text(open);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    inner.text(separator);
                }
                Scope loop = new Scope(scope);
                if (item != null) {
                    loop.put(item, items.get(i));
                }
                if (index != null) {
                    loop.put(index, i);
                }
                renderAll(children, loop, inner);
            }
            inner.text(close);
            out.append(inner);
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            collectExpression(collection, bound, out);
            Set<String> inner = new java.util.HashSet<>(bound);
            if (item != null) {
                inner.add(item);
            }
            if (index != null) {
                inner.add(index);
            }
            collectAll(children, inner, out);
        }
    }

    record Bind(String name, Expression value) implements SqlNode {
        public void render(Scope scope, SqlBuilder out) {
            scope.put(name, value.evaluate(scope));
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            collectExpression(value, bound, out);
            bound.add(name);
        }
    }

    /**
     * {@code <orderBy param="sort" allow="a,b" default="a desc"/>} (spec §7.2): the only way a
     * request value reaches SQL as text, and only as an allowed column name plus ASC/DESC.
     */
    record OrderBy(String param, List<String> allow, String defaultValue) implements SqlNode {

        private static final Pattern TERM = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)\\s*(?:\\s(asc|desc))?\\s*$",
                Pattern.CASE_INSENSITIVE);

        public void render(Scope scope, SqlBuilder out) {
            Object value = scope.apply(param);
            List<String> terms = new ArrayList<>();
            if (value instanceof Collection<?> c) {
                c.forEach(v -> terms.addAll(split(String.valueOf(v))));
            } else if (value != null && !value.toString().isBlank()) {
                terms.addAll(split(value.toString()));
            } else if (defaultValue != null) {
                terms.addAll(split(defaultValue));
            }
            if (terms.isEmpty()) {
                return;
            }
            SqlBuilder clause = new SqlBuilder();
            clause.text("ORDER BY " + String.join(", ", terms.stream().map(this::resolve).toList()));
            out.block(clause);
        }

        String resolve(String term) {
            Matcher m = TERM.matcher(term);
            String column = m.matches() ? m.group(1) : term.strip();
            String allowed = m.matches()
                    ? allow.stream().filter(a -> a.equalsIgnoreCase(column)).findFirst().orElse(null)
                    : null;
            if (allowed == null) {
                throw new TemplateException("'%s' is not sortable; choose from: %s."
                        .formatted(column, String.join(", ", allow)));
            }
            String direction = m.group(2);
            return direction == null ? allowed : allowed + " " + direction.toUpperCase(Locale.ROOT);
        }

        private static List<String> split(String value) {
            return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }

        public void collectParameters(Set<String> bound, Set<String> out) {
            if (!bound.contains(param)) {
                out.add(param);
            }
        }
    }

    /** Lists, sets and arrays as a list; anything else {@code null}. */
    static List<Object> asList(Object value) {
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
            int n = Array.getLength(value);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(Array.get(value, i));
            }
            return out;
        }
        return null;
    }
}
