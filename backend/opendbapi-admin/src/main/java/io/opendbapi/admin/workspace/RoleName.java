package io.opendbapi.admin.workspace;

import io.opendbapi.core.workspace.WorkspaceRole;

/** A workspace role as the API spells it (lowercase), shared by every view that shows one. */
public enum RoleName {
    owner, admin, script_author, author, viewer;

    public static RoleName of(WorkspaceRole role) {
        return valueOf(role.dbValue());
    }

    public WorkspaceRole toRole() {
        return WorkspaceRole.fromDb(name());
    }
}
