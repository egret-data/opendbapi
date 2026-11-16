package io.opendbapi.admin.auth;

import io.opendbapi.admin.security.SessionSignIn;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.identity.IdentityProviderStore;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in and sign-out (spec §21.1). Public: these run before there is a session. */
@RestController
@RequestMapping(path = "/admin/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth")
public class AuthController {

    public record ProviderRef(UUID id, String name) {
    }

    public record AuthProviders(boolean local, List<ProviderRef> ldap, List<ProviderRef> oidc) {
    }

    public enum LoginSource { local, ldap }

    public record LoginRequest(
            @NotNull LoginSource source,
            @Schema(nullable = true, description = "Required when source is ldap") UUID idpId,
            @NotBlank @Size(max = 256) String username,
            @NotBlank @Size(max = 256) String password) {
    }

    private final OpenDbApiProperties properties;
    private final IdentityProviderStore providers;
    private final LocalLogin localLogin;
    private final LdapLogin ldapLogin;
    private final ExternalSignIn externalSignIn;
    private final SessionSignIn sessions;
    private final UserStore users;
    private final AuditLog audit;

    public AuthController(OpenDbApiProperties properties, IdentityProviderStore providers, LocalLogin localLogin,
                          LdapLogin ldapLogin, ExternalSignIn externalSignIn, SessionSignIn sessions,
                          UserStore users, AuditLog audit) {
        this.properties = properties;
        this.providers = providers;
        this.localLogin = localLogin;
        this.ldapLogin = ldapLogin;
        this.externalSignIn = externalSignIn;
        this.sessions = sessions;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/providers")
    @Operation(operationId = "getAuthProviders", summary = "Sign-in methods to offer on the login page")
    public AuthProviders providers() {
        return new AuthProviders(
                properties.auth().localEnabled(),
                refs(providers.listEnabled(IdentityProvider.LDAP)),
                refs(providers.listEnabled(IdentityProvider.OIDC)));
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Sign in with a local account or an LDAP directory")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        UserAccount user;
        try {
            user = switch (body.source()) {
                case local -> {
                    UserAccount account = localLogin.authenticate(body.username(), body.password());
                    users.recordSuccessfulLogin(account.id());
                    audit.record(null, account.id(), "auth.login", "user", account.id().toString(), Map.of("source", "local"));
                    yield account;
                }
                case ldap -> {
                    IdentityProvider idp = ldapProvider(body.idpId());
                    yield externalSignIn.complete(idp, ldapLogin.authenticate(idp, body.username(), body.password()));
                }
            };
        } catch (ApiProblem e) {
            if (e.status() == 401 || e.status() == 429) {
                audit.record(null, null, "auth.login_failed", "username", body.username(),
                        Map.of("source", body.source().name(), "reason", e.code()));
            }
            throw e;
        }
        sessions.signIn(user, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "End the session")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof io.opendbapi.admin.security.SessionUser s) {
            audit.record(null, s.userId(), "auth.logout", "user", s.userId().toString(), Map.of());
        }
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }

    private IdentityProvider ldapProvider(UUID idpId) {
        if (idpId == null) {
            throw ApiProblem.badRequest("invalid_request", "idpId is required when source is ldap.");
        }
        return providers.find(idpId)
                .filter(p -> IdentityProvider.LDAP.equals(p.type()) && p.enabled())
                .orElseThrow(LocalLogin::invalid);
    }

    private static List<ProviderRef> refs(List<IdentityProvider> list) {
        return list.stream().map(p -> new ProviderRef(p.id(), p.name())).toList();
    }
}
