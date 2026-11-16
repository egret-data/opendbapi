package io.opendbapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything outside the management API: the SPA's static files, health/metrics probes and,
 * until serving authentication arrives (roadmap step 6), the serving surface. Stateless;
 * never creates or reads the admin session.
 */
@Configuration(proxyBeanMethods = false)
class PublicSurfaceSecurity {

    @Bean
    @Order(100)
    SecurityFilterChain publicSurface(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.requireExplicitSave(true))
                .build();
    }
}
