package io.opendbapi.sql.template;

import java.util.List;

/** SQL with {@code ?} placeholders and their values, in order, ready for a PreparedStatement. */
public record BoundSql(String sql, List<Object> parameters) {

    public BoundSql {
        parameters = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(parameters));
    }
}
