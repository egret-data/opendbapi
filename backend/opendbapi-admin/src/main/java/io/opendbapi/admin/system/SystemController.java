package io.opendbapi.admin.system;

import io.opendbapi.core.config.OpenDbApiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** {@code GET /admin/api/system/info} (spec §21.1). */
@RestController
@RequestMapping(path = "/admin/api/system", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "system")
public class SystemController {

    private final OpenDbApiProperties properties;
    private final String version;

    public SystemController(OpenDbApiProperties properties, ObjectProvider<BuildProperties> build) {
        this.properties = properties;
        BuildProperties info = build.getIfAvailable();
        this.version = info != null ? info.getVersion() : "dev";
    }

    @GetMapping("/info")
    @Operation(operationId = "systemInfo", summary = "Version, run mode and the public serving base URL")
    public SystemInfo info() {
        return new SystemInfo(
                version,
                properties.mode().name().toLowerCase(Locale.ROOT),
                servingBaseUrl());
    }

    private String servingBaseUrl() {
        String base = properties.serving().urlBase();
        if (base.isBlank()) {
            base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        }
        return stripTrailingSlash(base) + "/" + properties.serving().context();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record SystemInfo(String version, String mode, String servingBaseUrl) {
    }
}
