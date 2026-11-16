package io.opendbapi.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Top-level {@code opendbapi.*} settings (spec §22). */
@ConfigurationProperties(prefix = "opendbapi")
public record OpenDbApiProperties(
        @DefaultValue("standalone") Mode mode,
        /** Base64 of 32 random bytes; seals secrets stored as {@code enc:} references. */
        @DefaultValue("") String masterKey,
        @DefaultValue Serving serving,
        @DefaultValue Auth auth) {

    public enum Mode {
        STANDALONE,
        CLUSTER
    }

    /**
     * @param context first path segment of every published endpoint: {@code /{context}/{ws}/...}
     * @param urlBase public base URL shown to authors; empty means derive it from the request
     */
    public record Serving(
            @DefaultValue("api") String context,
            @DefaultValue("") String urlBase) {
    }

    /**
     * @param localEnabled offer local accounts on the sign-in page; system admins can always
     *     sign in locally (break-glass, spec §8.5)
     * @param lockoutAttempts consecutive local failures before the account is locked
     * @param lockoutDuration how long a locked local account stays locked
     * @param bootstrapAdminPassword password for the first system admin; random when empty
     * @param postLoginRedirect where the browser lands after SSO; the SPA root by default
     */
    public record Auth(
            @DefaultValue("true") boolean localEnabled,
            @DefaultValue("5") int lockoutAttempts,
            @DefaultValue("15m") Duration lockoutDuration,
            @DefaultValue("") String bootstrapAdminPassword,
            @DefaultValue("/") String postLoginRedirect) {
    }
}
