package io.opendbapi.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.core.client.AccessTokens;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.docs.DocsStore;
import io.opendbapi.core.docs.OpenApiBuilder;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.policy.ServingGuard;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.core.workspace.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * Generated API documentation of a workspace (spec §10). The document lists public endpoints
 * and, when a valid token is sent, the private endpoints its service account may call. Endpoint
 * paths cannot contain a dot, so {@code openapi.json} and {@code docs.html} never collide.
 */
@RestController
public class DocsController {

    static final String SWAGGER_UI = "/webjars/swagger-ui/5.17.14";

    private final WorkspaceStore workspaces;
    private final WorkspaceScope scope;
    private final DocsStore docs;
    private final ServiceAccountStore accounts;
    private final AccessTokens tokens;
    private final ServingGuard guard;
    private final OpenDbApiProperties properties;
    private final ObjectMapper json;
    private final boolean uiEnabled;

    public DocsController(WorkspaceStore workspaces, WorkspaceScope scope, DocsStore docs, ServiceAccountStore accounts,
                          AccessTokens tokens, ServingGuard guard, OpenDbApiProperties properties, ObjectMapper json,
                          @Value("${opendbapi.serving.docs-ui:true}") boolean uiEnabled) {
        this.workspaces = workspaces;
        this.scope = scope;
        this.docs = docs;
        this.accounts = accounts;
        this.tokens = tokens;
        this.guard = guard;
        this.properties = properties;
        this.json = json;
        this.uiEnabled = uiEnabled;
    }

    @GetMapping(path = "/${opendbapi.serving.context:api}/{ws}/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> openapi(@PathVariable String ws, HttpServletRequest request) {
        Workspace workspace = active(ws);
        guard.checkFirewall(workspace.id(), guard.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For")));
        Set<java.util.UUID> granted = Set.of();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            AccessTokens.Caller caller = tokens.verify(header.substring(7).strip());
            if (caller.workspaceId().equals(workspace.id())) {
                granted = scope.call(workspace.id(), () -> accounts.grantedEndpoints(caller.serviceAccountId()));
            }
        }
        Set<java.util.UUID> allowed = granted;
        List<DocsStore.DocEntry> entries = scope.call(workspace.id(), () -> docs.live(null)).stream()
                .filter(e -> "public".equals(e.snapshot().access()) || allowed.contains(e.endpointId()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(OpenApiBuilder.build(target(workspace), entries, json));
    }

    @GetMapping(path = "/${opendbapi.serving.context:api}/{ws}/docs.html")
    public ResponseEntity<String> ui(@PathVariable String ws) {
        if (!uiEnabled) {
            throw ApiProblem.notFound("not_found", "API docs are turned off on this server.");
        }
        Workspace workspace = active(ws);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        String title = HtmlUtils.htmlEscape(workspace.name() + " API");
        String specUrl = base + "/" + properties.serving().context() + "/" + ws + "/openapi.json";
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <link rel="stylesheet" href="%s%s/swagger-ui.css">
                </head>
                <body>
                  <div id="docs"></div>
                  <script src="%s%s/swagger-ui-bundle.js"></script>
                  <script>
                    SwaggerUIBundle({ url: %s, dom_id: "#docs", deepLinking: true, persistAuthorization: false,
                      tryItOutEnabled: false, requestInterceptor: function (r) { return r; } });
                  </script>
                </body>
                </html>
                """.formatted(title, base, SWAGGER_UI, base, SWAGGER_UI, jsString(specUrl));
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")
                .contentType(MediaType.TEXT_HTML).body(html);
    }

    private Workspace active(String slug) {
        return workspaces.findBySlug(slug).filter(Workspace::active)
                .orElseThrow(() -> ApiProblem.notFound("not_found", "No such workspace."));
    }

    private OpenApiBuilder.Target target(Workspace workspace) {
        String base = properties.serving().urlBase();
        if (base.isBlank()) {
            base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return new OpenApiBuilder.Target(workspace.name() + " API", base + "/" + properties.serving().context() + "/" + workspace.slug(),
                base + "/oauth2/token");
    }

    private String jsString(String s) {
        try {
            return json.writeValueAsString(s).replace("</", "<\\/");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
