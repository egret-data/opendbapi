package io.opendbapi.sql.template;

import java.util.ArrayList;
import java.util.List;

/** Accumulates rendered SQL and its bind values side by side. */
final class SqlBuilder {

    private final StringBuilder sql = new StringBuilder();
    private final List<Object> parameters = new ArrayList<>();
    /** A clause keyword block (WHERE/SET/ORDER BY) was just written; separate following text. */
    private boolean afterBlock;

    void text(String s) {
        if (s.isEmpty()) {
            return;
        }
        if (afterBlock && !Character.isWhitespace(s.charAt(0)) && endsWithNonSpace()) {
            sql.append(' ');
        }
        afterBlock = false;
        sql.append(s);
    }

    void parameter(Object value) {
        text("?");
        parameters.add(value);
    }

    /** Appends a clause block, separated from what precedes it by a space when needed. */
    void block(SqlBuilder content) {
        if (content.sql.isEmpty()) {
            return;
        }
        if (endsWithNonSpace()) {
            sql.append(' ');
        }
        sql.append(content.sql);
        parameters.addAll(content.parameters);
        afterBlock = true;
    }

    /** Appends another builder verbatim (no spacing rules). */
    void append(SqlBuilder other) {
        if (other.sql.isEmpty()) {
            return;
        }
        text(other.sql.toString());
        parameters.addAll(other.parameters);
    }

    String sql() {
        return sql.toString();
    }

    List<Object> parameters() {
        return parameters;
    }

    void replaceSql(String newSql) {
        sql.setLength(0);
        sql.append(newSql);
    }

    boolean isBlank() {
        return sql.toString().isBlank();
    }

    private boolean endsWithNonSpace() {
        return !sql.isEmpty() && !Character.isWhitespace(sql.charAt(sql.length() - 1));
    }
}
