package io.opendbapi.core.identity;

import java.util.UUID;

/**
 * Directory group → role. {@code workspaceId} is null exactly when {@code role} is
 * {@code system_admin}. {@code managed}: the mapping alone decides membership in that
 * workspace, overriding roles granted by hand (spec §8.5).
 */
public record GroupRoleMapping(UUID id, UUID idpId, String externalGroup, UUID workspaceId, String role, boolean managed) {

    public static final String SYSTEM_ADMIN = "system_admin";

    public boolean grantsSystemAdmin() {
        return SYSTEM_ADMIN.equals(role);
    }
}
