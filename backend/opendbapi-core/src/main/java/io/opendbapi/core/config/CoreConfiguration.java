package io.opendbapi.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenDbApiProperties.class)
public class CoreConfiguration {
}
