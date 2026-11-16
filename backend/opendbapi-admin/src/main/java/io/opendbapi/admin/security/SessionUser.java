package io.opendbapi.admin.security;

import java.io.Serializable;
import java.util.UUID;

/**
 * What the HTTP session remembers about the signed-in user: only the id. Everything else
 * (disabled flag, system-admin flag, workspace roles) is read from the database on each
 * request, so an admin's change takes effect immediately rather than at the next sign-in.
 */
public record SessionUser(UUID userId, String username) implements Serializable {
}
