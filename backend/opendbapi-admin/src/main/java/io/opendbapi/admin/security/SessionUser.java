package io.opendbapi.admin.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * What the HTTP session remembers about the signed-in user: only the id. Everything else
 * (disabled flag, system-admin flag, workspace roles) is read from the database on each
 * request, so an admin's change takes effect immediately rather than at the next sign-in.
 * As a {@link Principal} its name is the username, which the shared session store indexes.
 */
public record SessionUser(UUID userId, String username) implements Serializable, Principal {

    @Override
    public String getName() {
        return username;
    }
}
