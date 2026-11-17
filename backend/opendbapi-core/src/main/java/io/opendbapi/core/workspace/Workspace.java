package io.opendbapi.core.workspace;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record Workspace(
        UUID id,
        String slug,
        String name,
        String status,
        Map<String, Object> quotas,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        boolean configLocked) {

    public boolean active() {
        return "active".equals(status);
    }
}
