package io.opendbapi.admin.security;

import java.util.UUID;

/**
 * Set as authentication details when a request signs in with an API token (spec §8.6):
 * {@code workspaceId} limits it to one workspace; null allows every workspace of the user.
 */
public record TokenScope(UUID tokenId, UUID workspaceId) {

    /** The scope of the current request, or null when it uses a browser session. */
    public static TokenScope current() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof TokenScope s ? s : null;
    }
}
