package io.opendbapi.sql.template;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Variable lookup: request parameters, overlaid by {@code <bind>} and {@code <foreach>} names. */
final class Scope implements Function<String, Object> {

    private final Function<String, Object> parent;
    private final Map<String, Object> local = new HashMap<>();

    Scope(Function<String, Object> parent) {
        this.parent = parent;
    }

    void put(String name, Object value) {
        local.put(name, value);
    }

    @Override
    public Object apply(String name) {
        return local.containsKey(name) ? local.get(name) : parent.apply(name);
    }
}
