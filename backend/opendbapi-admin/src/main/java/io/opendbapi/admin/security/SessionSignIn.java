package io.opendbapi.admin.security;

import io.opendbapi.core.identity.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Starts and ends the browser session after any kind of sign-in (local, LDAP, OIDC). */
@Component
public class SessionSignIn {

    private final SecurityContextRepository contexts;

    public SessionSignIn(SecurityContextRepository contexts) {
        this.contexts = contexts;
    }

    public void signIn(UserAccount user, HttpServletRequest request, HttpServletResponse response) {
        // A new session id on every sign-in defeats session fixation.
        request.getSession(true);
        request.changeSessionId();
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.id(), user.username()), null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    public void signOut(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
