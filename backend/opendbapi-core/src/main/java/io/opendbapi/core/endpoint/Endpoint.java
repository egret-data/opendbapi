package io.opendbapi.core.endpoint;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The draft row of an endpoint plus what is live (spec §4.2, §5.1). */
public record Endpoint(
        UUID id,
        UUID workspaceId,
        UUID groupId,
        String name,
        String description,
        String method,
        String path,
        String state,
        UUID liveVersionId,
        Integer liveVersion,
        String access,
        EndpointDefinition definition,
        boolean dirty,
        OffsetDateTime deletedAt,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String DRAFT = "draft";
    public static final String LIVE = "live";
    public static final String DISABLED = "disabled";

    public boolean trashed() {
        return deletedAt != null;
    }
}
