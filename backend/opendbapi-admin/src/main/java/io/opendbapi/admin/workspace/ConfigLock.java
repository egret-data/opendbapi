package io.opendbapi.admin.workspace;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spec §15.2: in a workspace managed by configuration files, what the files describe —
 * endpoints and their tests, groups, service accounts and grants, data policies — changes
 * through {@code config:apply} only; editing them answers 409 {@code config_locked}. Checks,
 * previews, test runs, cache clears, Try-it and client credentials stay available, as do
 * datasources, secrets, members and settings, which belong to each environment.
 * {@code opendbapi.ui.read-only=true} locks every workspace.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigLock implements WebMvcConfigurer {

    private static final Pattern WS = Pattern.compile("^/admin/api/ws/([^/]+)/");
    /** Writes that change nothing the files describe. */
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile(":validate$"), Pattern.compile("/preview$"), Pattern.compile("/tests:run$"), Pattern.compile("/cache:clear$"),
            Pattern.compile("/endpoints/[^/]+/try(/.*)?$"), Pattern.compile("/service-accounts/[^/]+/clients(/.*)?$"));

    // Looked up when needed: web-layer test slices load WebMvcConfigurers without the stores.
    private final org.springframework.beans.factory.ObjectProvider<WorkspaceStore> workspaces;
    private final boolean serverWide;

    public ConfigLock(org.springframework.beans.factory.ObjectProvider<WorkspaceStore> workspaces,
                      @Value("${opendbapi.ui.read-only:false}") boolean serverWide) {
        this.workspaces = workspaces;
        this.serverWide = serverWide;
    }

    /** Whether the workspace is managed by configuration files only. */
    public boolean locked(Workspace w) {
        return serverWide || w.configLocked();
    }

    public boolean serverWide() {
        return serverWide;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String method = request.getMethod();
                if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
                    return true;
                }
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (ALLOWED.stream().anyMatch(p -> p.matcher(path).find())) {
                    return true;
                }
                var m = WS.matcher(path);
                WorkspaceStore store = workspaces.getIfAvailable();
                if (m.find() && store != null && store.findBySlug(m.group(1)).map(ConfigLock.this::locked).orElse(false)) {
                    throw ApiProblem.conflict("config_locked",
                            "This workspace is managed by configuration files: change them and run opendbapi apply.");
                }
                return true;
            }
        }).addPathPatterns("/admin/api/ws/*/endpoints", "/admin/api/ws/*/endpoints/**", "/admin/api/ws/*/groups",
                "/admin/api/ws/*/groups/**", "/admin/api/ws/*/service-accounts", "/admin/api/ws/*/service-accounts/**",
                "/admin/api/ws/*/data-policies", "/admin/api/ws/*/data-policies/**");
    }
}
