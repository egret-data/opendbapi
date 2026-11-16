package io.opendbapi.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Top-level {@code opendbapi.*} settings (spec §22). */
@ConfigurationProperties(prefix = "opendbapi")
public record OpenDbApiProperties(
        @DefaultValue("standalone") Mode mode,
        @DefaultValue Serving serving) {

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
}
