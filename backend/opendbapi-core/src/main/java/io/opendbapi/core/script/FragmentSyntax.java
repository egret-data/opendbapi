package io.opendbapi.core.script;

/**
 * Lets scripts write {@code sql"… ${v} …"} (spec §7.4), which Groovy itself does not parse:
 * an identifier glued to a string literal. Rewrites each one to {@code sql("…")}, skipping
 * comments and other string literals, and following nested {@code ${…}} with strings inside.
 */
final class FragmentSyntax {

    private FragmentSyntax() {
    }

    static String rewrite(String src) {
        StringBuilder out = new StringBuilder(src.length() + 16);
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = stringEnd(src, i);
                out.append(src, i, end);
                i = end;
            } else if (src.startsWith("sql", i) && (i == 0 || !Character.isJavaIdentifierPart(src.charAt(i - 1)))
                    && i + 3 < n && (src.charAt(i + 3) == '"' || src.charAt(i + 3) == '\'')) {
                int end = stringEnd(src, i + 3);
                out.append("sql(").append(src, i + 3, end).append(')');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index just past the string literal starting at {@code start} (single, double or triple quoted). */
    static int stringEnd(String s, int start) {
        char q = s.charAt(start);
        boolean triple = s.startsWith(String.valueOf(q).repeat(3), start);
        int i = start + (triple ? 3 : 1);
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (q == '"' && c == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
                i = interpolationEnd(s, i + 2);
            } else if (c == q && (!triple || s.startsWith(String.valueOf(q).repeat(3), i))) {
                return i + (triple ? 3 : 1);
            } else {
                i++;
            }
        }
        return n;
    }

    private static int interpolationEnd(String s, int i) {
        int depth = 1;
        int n = s.length();
        while (i < n && depth > 0) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = stringEnd(s, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        return i;
    }
}
