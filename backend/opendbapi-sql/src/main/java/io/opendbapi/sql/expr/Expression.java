package io.opendbapi.sql.expr;

import io.opendbapi.sql.template.TemplateException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * The small expression language of {@code <if test>}, {@code <when test>} and
 * {@code <bind value>} (spec §7.2), compatible with the MyBatis/OGNL expressions people
 * actually write:
 *
 * <pre>
 *   name != null and name != ''        ids.size() > 0        status == '1'
 *   age gte 18 &amp;&amp; !(flag)                  '%' + keyword + '%'   (page - 1) * size
 * </pre>
 *
 * <p>Deliberately not a general-purpose language: it reads variables and the keys/indices of
 * maps and lists, compares, combines and does decimal arithmetic. The only callable methods
 * are {@code size()}, {@code length()} and {@code isEmpty()}. There is no reflection, no
 * type reference and no object construction, so a template cannot reach the JVM.
 */
public final class Expression {

    private final String source;
    private final Node root;

    private Expression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new TemplateException("Expression is empty.");
        }
        return new Expression(source, new Parser(source).parseAll());
    }

    /** Evaluates against {@code variables}; an unknown variable reads as {@code null}. */
    public Object evaluate(Function<String, Object> variables) {
        return root.eval(variables);
    }

    /** Evaluates as a condition: null, false, 0, "" and empty collections are false. */
    public boolean test(Function<String, Object> variables) {
        return Values.truthy(evaluate(variables));
    }

    /** First path segment of every variable read, e.g. {@code b} for {@code b.c}. */
    public Set<String> rootVariables() {
        Set<String> names = new LinkedHashSet<>();
        root.collectVariables(names);
        return names;
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    // ------------------------------------------------------------------ values

    static final class Values {
        private Values() {
        }

        static boolean truthy(Object v) {
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof Number n) {
                return toDecimal(n).signum() != 0;
            }
            if (v instanceof CharSequence s) {
                return !s.isEmpty();
            }
            if (v instanceof Collection<?> c) {
                return !c.isEmpty();
            }
            if (v instanceof java.util.Map<?, ?> m) {
                return !m.isEmpty();
            }
            return true;
        }

        static BigDecimal toDecimal(Object v) {
            if (v instanceof BigDecimal d) {
                return d;
            }
            if (v instanceof Number n) {
                return n instanceof Double || n instanceof Float
                        ? BigDecimal.valueOf(n.doubleValue())
                        : new BigDecimal(n.toString());
            }
            if (v instanceof CharSequence s) {
                try {
                    return new BigDecimal(s.toString().trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        static boolean equal(Object a, Object b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a instanceof Number || b instanceof Number) {
                BigDecimal x = toDecimal(a);
                BigDecimal y = toDecimal(b);
                return x != null && y != null && x.compareTo(y) == 0;
            }
            if (a instanceof CharSequence || b instanceof CharSequence) {
                return a.toString().equals(b.toString());
            }
            return a.equals(b);
        }

        /** {@code null} when not comparable (a comparison with it is then false). */
        @SuppressWarnings({"unchecked", "rawtypes"})
        static Integer compare(Object a, Object b) {
            if (a == null || b == null) {
                return null;
            }
            if (a instanceof Number || b instanceof Number) {
                BigDecimal x = toDecimal(a);
                BigDecimal y = toDecimal(b);
                return x == null || y == null ? null : x.compareTo(y);
            }
            if (a instanceof CharSequence && b instanceof CharSequence) {
                return a.toString().compareTo(b.toString());
            }
            if (a instanceof Comparable c && a.getClass().isInstance(b)) {
                return c.compareTo(b);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ AST

    interface Node {
        Object eval(Function<String, Object> vars);

        default void collectVariables(Set<String> out) {
        }
    }

    record Literal(Object value) implements Node {
        public Object eval(Function<String, Object> vars) {
            return value;
        }
    }

    record Variable(String name) implements Node {
        public Object eval(Function<String, Object> vars) {
            return vars.apply(name);
        }

        public void collectVariables(Set<String> out) {
            out.add(name);
        }
    }

    /** {@code a.b}: map key only, never a Java getter. */
    record Property(Node target, String key) implements Node {
        public Object eval(Function<String, Object> vars) {
            return target.eval(vars) instanceof java.util.Map<?, ?> m ? m.get(key) : null;
        }

        public void collectVariables(Set<String> out) {
            target.collectVariables(out);
        }
    }

    /** {@code a[i]}: list/array index or map key. */
    record Index(Node target, Node index) implements Node {
        public Object eval(Function<String, Object> vars) {
            Object t = target.eval(vars);
            Object i = index.eval(vars);
            if (t instanceof java.util.Map<?, ?> m) {
                return i == null ? null : m.get(i.toString());
            }
            BigDecimal n = Values.toDecimal(i);
            if (n == null) {
                return null;
            }
            int at = n.intValue();
            if (t instanceof java.util.List<?> list) {
                return at >= 0 && at < list.size() ? list.get(at) : null;
            }
            if (t != null && t.getClass().isArray()) {
                return at >= 0 && at < java.lang.reflect.Array.getLength(t) ? java.lang.reflect.Array.get(t, at) : null;
            }
            return null;
        }

        public void collectVariables(Set<String> out) {
            target.collectVariables(out);
            index.collectVariables(out);
        }
    }

    record Call(Node target, String method) implements Node {
        public Object eval(Function<String, Object> vars) {
            Object t = target.eval(vars);
            int size;
            if (t == null) {
                return "isEmpty".equals(method) ? Boolean.TRUE : BigDecimal.ZERO;
            } else if (t instanceof Collection<?> c) {
                size = c.size();
            } else if (t instanceof java.util.Map<?, ?> m) {
                size = m.size();
            } else if (t instanceof CharSequence s) {
                size = s.length();
            } else if (t.getClass().isArray()) {
                size = java.lang.reflect.Array.getLength(t);
            } else {
                throw new TemplateException("%s() is not defined for %s.".formatted(method, t.getClass().getSimpleName()));
            }
            return "isEmpty".equals(method) ? Boolean.valueOf(size == 0) : BigDecimal.valueOf(size);
        }

        public void collectVariables(Set<String> out) {
            target.collectVariables(out);
        }
    }

    record Not(Node operand) implements Node {
        public Object eval(Function<String, Object> vars) {
            return !Values.truthy(operand.eval(vars));
        }

        public void collectVariables(Set<String> out) {
            operand.collectVariables(out);
        }
    }

    record Negate(Node operand) implements Node {
        public Object eval(Function<String, Object> vars) {
            BigDecimal d = Values.toDecimal(operand.eval(vars));
            if (d == null) {
                throw new TemplateException("Cannot negate a non-number.");
            }
            return d.negate();
        }

        public void collectVariables(Set<String> out) {
            operand.collectVariables(out);
        }
    }

    record Binary(String op, Node left, Node right) implements Node {
        public Object eval(Function<String, Object> vars) {
            switch (op) {
                case "and":
                    return Values.truthy(left.eval(vars)) && Values.truthy(right.eval(vars));
                case "or":
                    return Values.truthy(left.eval(vars)) || Values.truthy(right.eval(vars));
                default:
                    break;
            }
            Object a = left.eval(vars);
            Object b = right.eval(vars);
            return switch (op) {
                case "==" -> Values.equal(a, b);
                case "!=" -> !Values.equal(a, b);
                case "<" -> cmp(a, b, c -> c < 0);
                case "<=" -> cmp(a, b, c -> c <= 0);
                case ">" -> cmp(a, b, c -> c > 0);
                case ">=" -> cmp(a, b, c -> c >= 0);
                case "+" -> plus(a, b);
                default -> arithmetic(a, b);
            };
        }

        private static boolean cmp(Object a, Object b, java.util.function.IntPredicate ok) {
            Integer c = Values.compare(a, b);
            return c != null && ok.test(c);
        }

        private static Object plus(Object a, Object b) {
            if (a instanceof CharSequence || b instanceof CharSequence || a == null || b == null) {
                return (a == null ? "" : a.toString()) + (b == null ? "" : b.toString());
            }
            BigDecimal x = Values.toDecimal(a);
            BigDecimal y = Values.toDecimal(b);
            if (x == null || y == null) {
                return a.toString() + b;
            }
            return x.add(y);
        }

        private Object arithmetic(Object a, Object b) {
            BigDecimal x = Values.toDecimal(a);
            BigDecimal y = Values.toDecimal(b);
            if (x == null || y == null) {
                throw new TemplateException("Operator %s needs two numbers.".formatted(op));
            }
            if (("/".equals(op) || "%".equals(op)) && y.signum() == 0) {
                throw new TemplateException("Division by zero.");
            }
            return switch (op) {
                case "-" -> x.subtract(y);
                case "*" -> x.multiply(y);
                case "/" -> x.divide(y, java.math.MathContext.DECIMAL64);
                case "%" -> x.remainder(y);
                default -> throw new IllegalStateException(op);
            };
        }

        public void collectVariables(Set<String> out) {
            left.collectVariables(out);
            right.collectVariables(out);
        }
    }
}
