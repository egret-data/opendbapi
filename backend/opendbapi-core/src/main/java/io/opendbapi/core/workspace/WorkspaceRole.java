package io.opendbapi.core.workspace;

import java.util.Locale;

/** Roles inside one workspace, weakest first (spec §8.4). */
public enum WorkspaceRole {
    VIEWER,
    AUTHOR,
    SCRIPT_AUTHOR,
    ADMIN,
    OWNER;

    public boolean atLeast(WorkspaceRole minimum) {
        return compareTo(minimum) >= 0;
    }

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WorkspaceRole fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
