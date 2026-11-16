package io.opendbapi.core.datasource;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A workspace's connection to a customer database (spec §4.5). Only JDBC in P1. */
public record Datasource(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        String type,
        JdbcConfig config,
        String secretRef,
        boolean readOnly,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
