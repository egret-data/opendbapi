package io.opendbapi.core.script;

import io.opendbapi.sql.template.BoundSql;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A piece of SQL with its bind values (spec §7.4), built by {@code sql"…"} in a script. Values
 * interpolated with {@code ${…}} become {@code ?} parameters; a collection becomes {@code ?, ?, ?}.
 * Fragments only combine with fragments: adding a plain string is refused, so text from a caller
 * can never become SQL.
 */
public final class SqlFragment {

    private final String sql;
    private final List<Object> values;

    SqlFragment(String sql, List<Object> values) {
        this.sql = sql;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public SqlFragment plus(SqlFragment other) {
        List<Object> all = new ArrayList<>(values);
        all.addAll(other.values);
        return new SqlFragment(sql + other.sql, all);
    }

    /** Groovy's {@code fragment + "text"}: refused on purpose. */
    public SqlFragment plus(Object other) {
        throw new ScriptRejected("Only sql\"…\" fragments can be added to a fragment; wrap the text in sql\"…\", "
                + "and put values in ${…} so they are bound, not pasted.");
    }

    public BoundSql toBoundSql() {
        return new BoundSql(sql, values);
    }

    @Override
    public String toString() {
        return sql;
    }
}
