package io.opendbapi.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.identity.ApiTokenStore;
import io.opendbapi.core.identity.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Signs a request in with {@code Authorization: Bearer odb_…} (spec §8.6). The context is
 * request-only, never stored in a session; an unknown, revoked or expired token answers 401.
 */
public final class ApiTokenFilter extends OncePerRequestFilter {

    private final ApiTokenStore tokens;
    private final UserStore users;
    private final ObjectMapper json = new ObjectMapper();

    public ApiTokenFilter(ApiTokenStore tokens, UserStore users) {
        this.tokens = tokens;
        this.users = users;
    }

    /** Whether the request carries an API token; such requests need no CSRF token. */
    public static boolean hasToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer " + ApiTokenStore.PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasToken(request)) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("Authorization").substring("Bearer ".length()).strip();
        var grant = tokens.verify(token);
        var user = grant.flatMap(g -> users.findById(g.userId())).filter(u -> !u.disabled());
        if (grant.isEmpty() || user.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            json.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", "Unauthorized", "status", 401,
                    "code", "invalid_token", "detail", "The API token is unknown, revoked or expired."));
            return;
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(new SessionUser(user.get().id(), user.get().username()), null, List.of());
        auth.setDetails(new TokenScope(grant.get().tokenId(), grant.get().workspaceId()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
