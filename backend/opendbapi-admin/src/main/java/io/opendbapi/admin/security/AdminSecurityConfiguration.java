package io.opendbapi.admin.security;

import io.opendbapi.admin.auth.OidcRegistrations;
import io.opendbapi.admin.auth.OidcSignIn;
import io.opendbapi.admin.web.ProblemHandler;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.identity.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security of the management surface: {@code /admin/api/**} plus the OIDC callback.
 * Session cookie + CSRF (double-submit cookie {@code XSRF-TOKEN}); answers are RFC 9457
 * problems, never redirects or HTML. Authorisation beyond "signed in" is checked per
 * action through {@link CurrentUser} and {@link WorkspaceAccess}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

    public static final String OIDC_START_BASE = "/admin/api/auth/oidc";

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    @Order(1)
    SecurityFilterChain adminApi(HttpSecurity http, SecurityContextRepository contexts, OidcRegistrations registrations,
                                 OidcSignIn oidcSignIn, SessionSignIn sessions, UserStore users,
                                 OpenDbApiProperties properties, MappingJackson2HttpMessageConverter json)
            throws Exception {
        var authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(registrations, OIDC_START_BASE);
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http.securityMatcher("/admin/api/**", "/login/oauth2/**")
                .securityContext(c -> c.securityContextRepository(contexts))
                .csrf(c -> c
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // Plain (non-masked) tokens: the SPA echoes the cookie value as is.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/admin/api/auth/**", "/admin/api/system/info", "/admin/api/openapi.json",
                                "/login/oauth2/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(json, res, HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(json, res, HttpStatus.FORBIDDEN, "csrf",
                                "The request was refused because its CSRF token is missing or stale. Reload the page and try again.")))
                .oauth2Login(o -> o
                        .clientRegistrationRepository(registrations)
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(u -> u.oidcUserService(oidcSignIn))
                        .successHandler((req, res, auth) -> {
                            var provisioned = (OidcSignIn.ProvisionedOidcUser) auth.getPrincipal();
                            sessions.signIn(users.findById(provisioned.accountId()).orElseThrow(), req, res);
                            res.sendRedirect(properties.auth().postLoginRedirect());
                        })
                        .failureHandler((req, res, ex) -> {
                            String reason = ex.getMessage() == null ? "Single sign-on failed." : ex.getMessage();
                            res.sendRedirect("/login?error=sso&reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8));
                        }))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static void writeProblem(MappingJackson2HttpMessageConverter json, HttpServletResponse res,
                                     HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail body = ProblemHandler.problem(status, code, detail, null).getBody();
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        json.write(body, org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(res));
    }

    /** Makes sure the {@code XSRF-TOKEN} cookie is issued on any response, including the first GET. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
