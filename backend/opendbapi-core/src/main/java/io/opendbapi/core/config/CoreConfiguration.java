package io.opendbapi.core.config;

import io.opendbapi.core.datasource.HostPolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenDbApiProperties.class)
public class CoreConfiguration {

    @Bean
    HostPolicy datasourceHostPolicy(OpenDbApiProperties properties) {
        List<String> denied = new ArrayList<>(HostPolicy.DEFAULT_DENIED);
        denied.addAll(properties.datasources().extraDeniedHosts());
        return new HostPolicy(denied, properties.datasources().allowedHosts());
    }
}
