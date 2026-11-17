package io.opendbapi.core.script;

import groovy.lang.GString;
import groovy.lang.Script;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Base class of fragment scripts: the only functions a script can call without a receiver
 * (the sandbox refuses any other). {@code params} is a read-only map of the bound parameters.
 */
public abstract class FragmentScript extends Script {

    /** {@code sql"… ${value} …"}: text from the script, values bound as parameters. */
    public SqlFragment sql(GString template) {
        String[] strings = template.getStrings();
        Object[] values = template.getValues();
        StringBuilder sql = new StringBuilder();
        List<Object> bound = new ArrayList<>();
        for (int i = 0; i < strings.length; i++) {
            sql.append(strings[i]);
            if (i < values.length) {
                Object v = values[i];
                if (v instanceof SqlFragment f) {
                    throw new ScriptRejected("Combine fragments with +, not by interpolating one into another.");
                }
                if (v instanceof Collection<?> c) {
                    if (c.isEmpty()) {
                        throw new ScriptRejected("An empty list cannot be bound; check it before using it in sql\"…\".");
                    }
                    List<String> marks = new ArrayList<>();
                    for (Object item : c) {
                        marks.add("?");
                        bound.add(plain(item));
                    }
                    sql.append(String.join(", ", marks));
                } else {
                    sql.append('?');
                    bound.add(plain(v));
                }
            }
        }
        return new SqlFragment(sql.toString(), bound);
    }

    /** {@code sql"…"} without interpolation compiles to a plain String. */
    public SqlFragment sql(String text) {
        return new SqlFragment(text, List.of());
    }

    /** SQL text the author wrote; the sandbox only accepts a string literal here. */
    public SqlFragment raw(String text) {
        return new SqlFragment(text, List.of());
    }

    /**
     * {@code " ORDER BY <choice>"} where the choice is a value of {@code allowed}, picked by the
     * caller's {@code key}; an unknown or missing key falls back to {@code fallback}.
     */
    public SqlFragment orderBy(Map<?, ?> allowed, Object key, String fallback) {
        Object choice = key == null ? null : allowed.get(String.valueOf(key));
        String clause = choice == null ? fallback : String.valueOf(choice);
        return new SqlFragment(clause == null || clause.isBlank() ? "" : " order by " + clause, List.of());
    }

    private static Object plain(Object v) {
        return v instanceof GString g ? g.toString() : v;
    }
}
