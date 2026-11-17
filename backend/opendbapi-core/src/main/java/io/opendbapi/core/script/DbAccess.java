package io.opendbapi.core.script;

import java.util.List;
import java.util.Map;

/**
 * What a full-mode script reaches as {@code db} (spec §7.4): every query goes through the
 * endpoint's datasource, limits and data policies. The sandbox only lets a script call these
 * four methods on {@code db}.
 */
public interface DbAccess {

    /** The first row, or null when there is none. */
    Map<String, Object> one(SqlFragment query);

    /** Every row, up to the endpoint's row limit. */
    List<Map<String, Object>> list(SqlFragment query);

    /** The first column of the first row, or null. */
    Object value(SqlFragment query);

    /** Rows changed by an INSERT, UPDATE or DELETE. */
    long update(SqlFragment query);

    /** A failure of the database work, passed through the script unchanged. */
    final class Failure extends RuntimeException {
        public Failure(RuntimeException cause) {
            super(cause.getMessage(), cause);
        }

        public RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
    }
}
