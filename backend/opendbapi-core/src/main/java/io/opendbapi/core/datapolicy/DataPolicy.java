package io.opendbapi.core.datapolicy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A column mask or a row filter (spec §12). A mask belongs to a datasource (its column is a
 * table column, {@code [schema.]table.column} or just {@code column}) or to an endpoint (its
 * column is a result column). A row filter belongs to an endpoint.
 */
public record DataPolicy(
        UUID id,
        @Schema(allowableValues = {"mask", "row_filter"}) String kind,
        @Schema(nullable = true) UUID datasourceId,
        @Schema(nullable = true) UUID endpointId,
        String column,
        @Schema(nullable = true, description = "For kind mask") Mask mask,
        @Schema(nullable = true, description = "For kind row_filter: the service account attribute holding the allowed value(s)")
        String attribute,
        @Schema(description = "Service accounts this policy does not apply to") List<UUID> exemptServiceAccounts,
        Instant createdAt,
        Instant updatedAt) {

    public static final String MASK = "mask";
    public static final String ROW_FILTER = "row_filter";

    /**
     * @param keepStart for {@code partial}: characters kept at the start
     * @param keepEnd   for {@code partial}: characters kept at the end
     */
    public record Mask(@Schema(allowableValues = {"phone", "email", "partial", "hash", "nullify"}) String type,
                       @Schema(nullable = true) Integer keepStart, @Schema(nullable = true) Integer keepEnd) {
    }

    public boolean appliesTo(UUID serviceAccountId) {
        return serviceAccountId == null || !exemptServiceAccounts.contains(serviceAccountId);
    }
}
