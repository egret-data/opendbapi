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
        @DefaultValue Auth auth,
        @DefaultValue Datasources datasources) {

    public enum Mode {
        STANDALONE,
        CLUSTER
    }

    /**
     * @param context first path segment of every published endpoint: {@code /{context}/{ws}/...}
     * @param urlBase public base URL shown to authors; empty means derive it from the request
     * @param trustedProxies IPs or CIDR ranges of reverse proxies whose X-Forwarded-For is
     *     believed (spec §14); empty = the TCP peer is the caller
     */
    public record Serving(
            @DefaultValue("api") String context,
            @DefaultValue("") String urlBase,
            java.util.List<String> trustedProxies) {
        public Serving {
            trustedProxies = trustedProxies == null ? java.util.List.of() : trustedProxies;
        }
    }

    /**
     * Where datasources may connect (spec §4.6). Cloud metadata endpoints are always denied.
     *
     * @param extraDeniedHosts host names, {@code *.suffix} wildcards or CIDR ranges denied in
     *     addition to the defaults, e.g. the network of the metadata database
     * @param allowedHosts when not empty, the only hosts datasources may reach
     */
    public record Datasources(java.util.List<String> extraDeniedHosts, java.util.List<String> allowedHosts) {
        public Datasources {
            extraDeniedHosts = extraDeniedHosts == null ? java.util.List.of() : extraDeniedHosts;
            allowedHosts = allowedHosts == null ? java.util.List.of() : allowedHosts;
        }
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
