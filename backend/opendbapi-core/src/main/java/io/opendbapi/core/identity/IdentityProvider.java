package io.opendbapi.core.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An LDAP directory or OIDC issuer users can sign in through (spec §8.5). {@code config}
 * holds the non-secret settings; {@code secretRef} the bind password or client secret.
 */
public record IdentityProvider(
        UUID id,
        String type,
        String name,
        boolean enabled,
        JsonNode config,
        String secretRef,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String LDAP = "ldap";
    public static final String OIDC = "oidc";

    public String configText(String field, String fallback) {
        JsonNode node = config.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? fallback : node.asText();
    }
}
