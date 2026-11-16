package io.opendbapi.sql.expr;

import io.opendbapi.sql.template.TemplateException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser. Precedence, loosest first:
 * {@code or} → {@code and} → equality → relational → additive → multiplicative → unary → postfix.
 */
final class Parser {

    private static final Set<String> METHODS = Set.of("size", "length", "isEmpty");
    private static final Map<String, String> WORD_OPERATORS = Map.of(
            "and", "and", "or", "or", "eq", "==", "neq", "!=", "lt", "<", "lte", "<=", "gt", ">", "gte", ">=");

    private final String src;
    private int pos;

    Parser(String src) {
        this.src = src;
    }

    Expression.Node parseAll() {
        Expression.Node node = or();
        skipSpace();
        if (pos < src.length()) {
            throw error("Unexpected '" + src.charAt(pos) + "'");
        }
        return node;
    }

    private Expression.Node or() {
        Expression.Node left = and();
        while (acceptOperator("||") || acceptWord("or")) {
            left = new Expression.Binary("or", left, and());
        }
        return left;
    }

    private Expression.Node and() {
        Expression.Node left = equality();
        while (acceptOperator("&&") || acceptWord("and")) {
            left = new Expression.Binary("and", left, equality());
        }
        return left;
    }

    private Expression.Node equality() {
        Expression.Node left = relational();
        while (true) {
            String op = acceptOneOf("==", "!=", "eq", "neq");
            if (op == null) {
                return left;
            }
            left = new Expression.Binary(op, left, relational());
        }
    }

    private Expression.Node relational() {
        Expression.Node left = additive();
        while (true) {
            String op = acceptOneOf("<=", ">=", "<", ">", "lte", "gte", "lt", "gt");
            if (op == null) {
                return left;
            }
            left = new Expression.Binary(op, left, additive());
        }
    }

    private Expression.Node additive() {
        Expression.Node left = multiplicative();
        while (true) {
            String op = acceptOneOf("+", "-");
            if (op == null) {
                return left;
            }
            left = new Expression.Binary(op, left, multiplicative());
        }
    }

    private Expression.Node multiplicative() {
        Expression.Node left = unary();
        while (true) {
            String op = acceptOneOf("*", "/", "%");
            if (op == null) {
                return left;
            }
            left = new Expression.Binary(op, left, unary());
        }
    }

    private Expression.Node unary() {
        if (acceptWord("not") || acceptOperator("!")) {
            return new Expression.Not(unary());
        }
        if (acceptOperator("-")) {
            return new Expression.Negate(unary());
        }
        return postfix(primary());
    }

    private Expression.Node postfix(Expression.Node node) {
        while (true) {
            skipSpace();
            if (peek('.')) {
                pos++;
                int at = pos;
                String name = identifier();
                if (name == null) {
                    throw error("Expected a name after '.'");
                }
                skipSpace();
                if (peek('(')) {
                    if (!METHODS.contains(name)) {
                        pos = at;
                        throw error("Only size(), length() and isEmpty() can be called, not " + name + "()");
                    }
                    pos++;
                    expect(')');
                    node = new Expression.Call(node, name);
                } else {
                    node = new Expression.Property(node, name);
                }
            } else if (peek('[')) {
                pos++;
                Expression.Node index = or();
                expect(']');
                node = new Expression.Index(node, index);
            } else {
                return node;
            }
        }
    }

    private Expression.Node primary() {
        skipSpace();
        if (pos >= src.length()) {
            throw error("Expected a value");
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expression.Node inner = or();
            expect(')');
            return inner;
        }
        if (c == '\'' || c == '"') {
            return new Expression.Literal(string(c));
        }
        if (Character.isDigit(c)) {
            return new Expression.Literal(number());
        }
        int at = pos;
        String name = identifier();
        if (name == null) {
            throw error("Unexpected '" + c + "'");
        }
        switch (name) {
            case "null":
                return new Expression.Literal(null);
            case "true":
                return new Expression.Literal(Boolean.TRUE);
            case "false":
                return new Expression.Literal(Boolean.FALSE);
            case "new":
                pos = at;
                throw error("Creating objects is not allowed");
            default:
                break;
        }
        skipSpace();
        if (peek('(')) {
            pos = at;
            throw error("Calling " + name + "() is not allowed; only size(), length() and isEmpty() exist");
        }
        return new Expression.Variable(name);
    }

    private String string(char quote) {
        pos++;
        StringBuilder out = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                out.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> e;
                });
            } else if (c == quote) {
                if (pos < src.length() && src.charAt(pos) == quote) {
                    out.append(quote);
                    pos++;
                } else {
                    return out.toString();
                }
            } else {
                out.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private BigDecimal number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        try {
            return new BigDecimal(src.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("Bad number");
        }
    }

    private String identifier() {
        skipSpace();
        int start = pos;
        if (pos < src.length() && (Character.isLetter(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return src.substring(start, pos);
        }
        return null;
    }

    private String acceptOneOf(String... ops) {
        for (String op : ops) {
            boolean word = Character.isLetter(op.charAt(0));
            if (word ? acceptWord(op) : acceptOperator(op)) {
                return WORD_OPERATORS.getOrDefault(op, op);
            }
        }
        return null;
    }

    /** Symbol operator, not mistaken for the start of a longer one ({@code <} vs {@code <=}, {@code !} vs {@code !=}). */
    private boolean acceptOperator(String op) {
        skipSpace();
        if (!src.startsWith(op, pos)) {
            return false;
        }
        int end = pos + op.length();
        if (end < src.length()) {
            char next = src.charAt(end);
            boolean extends1 = ("<>!=".indexOf(op.charAt(op.length() - 1)) >= 0 && next == '=')
                    || (op.equals("&") && next == '&') || (op.equals("|") && next == '|');
            if (op.length() == 1 && extends1) {
                return false;
            }
            if (op.equals(">") && next == '>' || op.equals("<") && next == '<') {
                throw error("Unknown operator '" + op + next + "'");
            }
        }
        pos = end;
        return true;
    }

    private boolean acceptWord(String word) {
        skipSpace();
        if (!src.regionMatches(pos, word, 0, word.length())) {
            return false;
        }
        int end = pos + word.length();
        if (end < src.length() && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '_')) {
            return false;
        }
        pos = end;
        return true;
    }

    private void expect(char c) {
        skipSpace();
        if (!peek(c)) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private void skipSpace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private TemplateException error(String what) {
        return new TemplateException("%s at column %d of expression: %s".formatted(what, pos + 1, src));
    }
}
