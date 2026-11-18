package io.opendbapi.core.datasource;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A workspace's database or HTTP API (spec §4.6). {@code type} is {@code jdbc}, with
 * {@code config}, or {@code http}, with {@code http}; the other is null.
 */
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
        OffsetDateTime updatedAt,
        HttpConfig http) {

    public static final String JDBC = "jdbc";
    public static final String HTTP = "http";

    public Datasource(UUID id, UUID workspaceId, String name, String description, String type, JdbcConfig config, String secretRef,
                      boolean readOnly, UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, workspaceId, name, description, type, config, secretRef, readOnly, createdBy, createdAt, updatedAt, null);
    }

    public boolean isHttp() {
        return HTTP.equals(type);
    }
}
