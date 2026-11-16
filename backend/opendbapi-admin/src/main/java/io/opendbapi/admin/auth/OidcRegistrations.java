package io.opendbapi.admin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.identity.IdentityProviderStore;
import io.opendbapi.core.secret.SecretResolver;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;

/**
 * OIDC clients come from the {@code identity_provider} table, not from configuration files,
 * so administrators add SSO from the UI (spec §8.5). The registration id is the provider's id.
 * Discovery documents are cached until the provider row changes.
 *
 * <p>Config keys: {@code issuerUri}, {@code clientId}, {@code scopes} (default openid, profile,
 * email), {@code usernameClaim} (preferred_username), {@code groupsClaim} (groups). Client
 * secret: the provider's secret reference.
 */
@Component
public class OidcRegistrations implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(OidcRegistrations.class);

    private record Cached(OffsetDateTime version, ClientRegistration registration) {
    }

    private final IdentityProviderStore providers;
    private final SecretResolver secrets;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public OidcRegistrations(IdentityProviderStore providers, SecretResolver secrets) {
        this.providers = providers;
        this.secrets = secrets;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        UUID id;
        try {
            id = UUID.fromString(registrationId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        IdentityProvider idp = providers.find(id)
                .filter(p -> IdentityProvider.OIDC.equals(p.type()) && p.enabled())
                .orElse(null);
        if (idp == null) {
            cache.remove(id);
            return null;
        }
        Cached cached = cache.get(id);
        if (cached != null && cached.version().equals(idp.updatedAt())) {
            return cached.registration();
        }
        try {
            ClientRegistration registration = build(idp);
            cache.put(id, new Cached(idp.updatedAt(), registration));
            return registration;
        } catch (RuntimeException e) {
            log.warn("OIDC provider '{}' cannot be loaded: {}", idp.name(), e.getMessage());
            return null;
        }
    }

    /** Fetches the issuer's discovery document; used by the admin "Test" action too. */
    public ClientRegistration build(IdentityProvider idp) {
        List<String> scopes = new ArrayList<>();
        JsonNode configured = idp.config().path("scopes");
        if (configured.isArray() && !configured.isEmpty()) {
            configured.forEach(s -> scopes.add(s.asText()));
        } else {
            scopes.addAll(List.of("openid", "profile", "email"));
        }
        return ClientRegistrations.fromIssuerLocation(idp.configText("issuerUri", ""))
                .registrationId(idp.id().toString())
                .clientName(idp.name())
                .clientId(idp.configText("clientId", ""))
                .clientSecret(secrets.resolve(idp.secretRef()))
                .scope(scopes)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .userNameAttributeName(idp.configText("usernameClaim", "preferred_username"))
                .build();
    }
}
