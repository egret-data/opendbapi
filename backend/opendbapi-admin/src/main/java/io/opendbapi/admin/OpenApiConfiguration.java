package io.opendbapi.admin;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI managementApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenDBAPI management API")
                        .description("Authoring and administration API consumed by the OpenDBAPI web UI.")
                        .version("v1"))
                // Relative, so the committed document does not depend on the host or port.
                .servers(List.of(new Server().url("/")));
    }

    /**
     * DTOs are Java records whose components are never null unless declared nullable, so
     * every property is required. Without this the generated TypeScript types would make
     * every field optional and push pointless null checks into the UI.
     */
    @Bean
    OpenApiCustomizer recordPropertiesAreRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                if (schema.getProperties() == null) {
                    continue;
                }
                List<String> required = new ArrayList<>();
                schema.getProperties().forEach((name, property) -> {
                    // A $ref cannot carry "nullable" in OpenAPI 3.0; free-form JSON is always optional.
                    boolean freeJson = property.get$ref() != null && property.get$ref().endsWith("/JsonNode");
                    if (!Boolean.TRUE.equals(property.getNullable()) && !freeJson) {
                        required.add(name);
                    }
                    // Map<String, Object>: values may be any JSON, not only objects.
                    if (property.getAdditionalProperties() instanceof Schema<?> values
                            && "object".equals(values.getType()) && values.getProperties() == null
                            && values.get$ref() == null) {
                        property.setAdditionalProperties(true);
                    }
                });
                schema.setRequired(required.stream().sorted().toList());
            }
        };
    }
}
