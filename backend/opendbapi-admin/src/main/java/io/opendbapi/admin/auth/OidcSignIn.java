package io.opendbapi.admin.auth;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.identity.IdentityProviderStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Turns a verified OIDC login into an OpenDBAPI user (provisioning + role sync). */
@Component
public class OidcSignIn implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** The IdP's user, carrying the OpenDBAPI account it was mapped to. */
    public static final class ProvisionedOidcUser extends DefaultOidcUser {
        private final UUID accountId;

        ProvisionedOidcUser(UUID accountId, OidcUser source, String nameAttribute) {
            super(source.getAuthorities(), source.getIdToken(), source.getUserInfo(), nameAttribute);
            this.accountId = accountId;
        }

        public UUID accountId() {
            return accountId;
        }
    }

    private final OidcUserService delegate = new OidcUserService();
    private final IdentityProviderStore providers;
    private final ExternalSignIn externalSignIn;

    public OidcSignIn(IdentityProviderStore providers, ExternalSignIn externalSignIn) {
        this.providers = providers;
        this.externalSignIn = externalSignIn;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser oidcUser = delegate.loadUser(request);
        IdentityProvider idp = providers.find(UUID.fromString(request.getClientRegistration().getRegistrationId()))
                .orElseThrow(() -> fail("unknown_provider", "This sign-in provider no longer exists."));
        String usernameClaim = idp.configText("usernameClaim", "preferred_username");
        String username = oidcUser.getClaimAsString(usernameClaim);
        if (username == null || username.isBlank()) {
            throw fail("missing_claim", "The identity provider did not send the '%s' claim.".formatted(usernameClaim));
        }
        var identity = new ExternalSignIn.Identity(
                oidcUser.getSubject(),
                username,
                oidcUser.getFullName(),
                oidcUser.getEmail(),
                groups(oidcUser.getClaims().get(idp.configText("groupsClaim", "groups"))));
        try {
            var account = externalSignIn.complete(idp, identity);
            return new ProvisionedOidcUser(account.id(), oidcUser, usernameClaim);
        } catch (ApiProblem e) {
            throw fail(e.code(), e.getMessage());
        }
    }

    /** A groups claim may be a list or a single string; Keycloak full paths lose their leading slash. */
    static Collection<String> groups(Object claim) {
        List<String> out = new ArrayList<>();
        if (claim instanceof Collection<?> values) {
            values.forEach(v -> out.add(strip(String.valueOf(v))));
        } else if (claim instanceof String value && !value.isBlank()) {
            out.add(strip(value));
        }
        return out;
    }

    private static String strip(String group) {
        return group.startsWith("/") ? group.substring(1) : group;
    }

    private static OAuth2AuthenticationException fail(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null), description);
    }
}
