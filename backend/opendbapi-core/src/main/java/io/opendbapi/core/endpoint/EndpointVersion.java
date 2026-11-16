package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A frozen published definition. {@code snapshot} holds the definition plus the HTTP identity
 * (method, path, access) at publish time; {@code outputSchema} the result columns.
 */
public record EndpointVersion(
        UUID id,
        UUID endpointId,
        int version,
        Snapshot snapshot,
        JsonNode outputSchema,
        String note,
        UUID publishedBy,
        String publishedByName,
        OffsetDateTime publishedAt) {

    public record Snapshot(String method, String path, String access, EndpointDefinition definition) {
    }
}
