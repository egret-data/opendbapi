package io.opendbapi.admin.security;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in user, freshly loaded; a disabled or deleted account counts as signed out. */
@Component
public class CurrentUser {

    private final UserStore users;

    public CurrentUser(UserStore users) {
        this.users = users;
    }

    public UserAccount require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SessionUser session)) {
            throw new ApiProblem(401, "unauthenticated", "Sign in to continue.");
        }
        return users.findById(session.userId())
                .filter(u -> !u.disabled())
                .orElseThrow(() -> new ApiProblem(401, "unauthenticated", "Your account is no longer active."));
    }

    public UserAccount requireSystemAdmin() {
        UserAccount user = require();
        TokenScope token = TokenScope.current();
        if (token != null && token.workspaceId() != null) {
            throw ApiProblem.forbidden("This API token is limited to one workspace.");
        }
        if (!user.systemAdmin()) {
            throw ApiProblem.forbidden("This action is for system administrators.");
        }
        return user;
    }
}
