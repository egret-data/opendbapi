package io.opendbapi.core.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String email,
        String displayName,
        String source,
        UUID idpId,
        String externalId,
        String passwordHash,
        boolean systemAdmin,
        boolean disabled,
        int failedLogins,
        OffsetDateTime lockedUntil,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt) {

    public boolean local() {
        return "local".equals(source);
    }

    public boolean lockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
