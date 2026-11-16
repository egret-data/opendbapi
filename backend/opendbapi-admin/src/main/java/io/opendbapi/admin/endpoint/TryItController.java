package io.opendbapi.admin.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.client.AccessTokens;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.client.TryItRelay;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.endpoint.Endpoint;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.EndpointVersion;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.policy.ServingGuard;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Try-it (spec §11.2): calls the live version through the real serving pipeline of this
 * process, over loopback HTTP, with a 60-second token minted for the chosen service account.
 * Nothing is skipped: a missing grant gives the real 403. The token never reaches the browser.
 */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/endpoints/{id}/try", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "endpoints")
public class TryItController {

    /**
     * @param serviceAccountId who the call is made as; required for private endpoints
     * @param params parameter values by name, sent where each parameter is declared (path, query, header, body)
     */
    public record TryRequest(@Schema(nullable = true) UUID serviceAccountId,
                             @Schema(nullable = true) Map<String, Object> params,
                             @Schema(nullable = true) Integer page,
                             @Schema(nullable = true) Integer size) {
    }

    /**
     * @param body the response body as JSON, when it is JSON
     * @param curl the same call for a terminal, with the token as {@code $TOKEN}
     */
    public record TryResponse(int status, long durationMs, String method, String url, Map<String, String> headers,
                              @Schema(nullable = true) JsonNode body, @Schema(nullable = true) String text, String curl,
                              @Schema(nullable = true) String serviceAccountName) {
    }

    private static final List<String> SHOWN_HEADERS = List.of("content-type", "ratelimit-limit", "ratelimit-remaining",
            "ratelimit-reset", "retry-after", "www-authenticate");

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final EndpointStore endpoints;
    private final ServiceAccountStore accounts;
    private final AccessTokens tokens;
    private final TryItRelay relay;
    private final ServingGuard guard;
    private final OpenDbApiProperties properties;
    private final Environment environment;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public TryItController(WorkspaceAccess access, WorkspaceScope scope, EndpointStore endpoints, ServiceAccountStore accounts,
                           AccessTokens tokens, TryItRelay relay, ServingGuard guard, OpenDbApiProperties properties,
                           Environment environment, AuditLog audit, ObjectMapper json) {
        this.access = access;
        this.scope = scope;
        this.endpoints = endpoints;
        this.accounts = accounts;
        this.tokens = tokens;
        this.relay = relay;
        this.guard = guard;
        this.properties = properties;
        this.environment = environment;
        this.audit = audit;
        this.json = json;
    }

    @PostMapping
    @Operation(operationId = "tryEndpoint", summary = "Call the live version through the serving pipeline (author)")
    public TryResponse tryIt(@PathVariable String ws, @PathVariable UUID id, @RequestBody TryRequest body,
                             HttpServletRequest adminRequest) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        UUID wsId = granted.workspace().id();
        record Target(Endpoint endpoint, EndpointVersion.Snapshot live, String accountName) {
        }
        Target target = scope.call(wsId, () -> {
            Endpoint e = endpoints.find(id).filter(x -> !x.trashed())
                    .orElseThrow(() -> ApiProblem.notFound("endpoint_not_found", "That endpoint does not exist."));
            if (e.liveVersion() == null) {
                throw ApiProblem.conflict("not_published", "Publish the endpoint first: Try-it calls the live version.");
            }
            EndpointVersion v = endpoints.version(id, e.liveVersion()).orElseThrow();
            String name = null;
            if (body.serviceAccountId() != null) {
                name = accounts.find(body.serviceAccountId())
                        .orElseThrow(() -> ApiProblem.notFound("service_account_not_found", "That service account does not exist."))
                        .name();
            }
            return new Target(e, v.snapshot(), name);
        });
        EndpointVersion.Snapshot live = target.live();
        boolean isPrivate = !"public".equals(live.access());
        if (isPrivate && body.serviceAccountId() == null) {
            throw ApiProblem.badRequest("service_account_required", "Choose the service account to call this private endpoint as.");
        }

        Built built = build(ws, live, body);
        String loopback = loopbackBase() + built.pathAndQuery();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(loopback))
                .timeout(Duration.ofSeconds(live.definition().limits().timeoutSeconds() + 10L))
                .header("Accept", "application/json")
                .header(TryItRelay.HEADER, relay.header(granted.user().id(),
                        guard.clientIp(adminRequest.getRemoteAddr(), adminRequest.getHeader("X-Forwarded-For"))));
        built.headers().forEach(request::header);
        if (isPrivate || body.serviceAccountId() != null) {
            request.header("Authorization", "Bearer " + tokens.issueForTryIt(body.serviceAccountId(), granted.user().id(), wsId).token());
        }
        String jsonBody = built.body() == null ? null : built.body().toString();
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(live.method(), jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiProblem(502, "try_failed", "The serving pipeline could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiProblem(503, "try_failed", "Interrupted.");
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : SHOWN_HEADERS) {
            response.headers().firstValue(name).ifPresent(v -> headers.put(name, v));
        }
        JsonNode parsed = null;
        String text = null;
        try {
            parsed = response.body().isEmpty() ? null : json.readTree(response.body());
        } catch (IOException e) {
            text = response.body();
        }
        String publicUrl = publicBase() + built.pathAndQuery();
        Map<String, Object> detail = new HashMap<>();
        detail.put("serviceAccountId", body.serviceAccountId() == null ? null : body.serviceAccountId().toString());
        detail.put("status", response.statusCode());
        detail.put("version", target.endpoint().liveVersion());
        scope.run(wsId, () -> audit.record(wsId, granted.user().id(), "endpoint.try", "endpoint", id.toString(), detail));
        return new TryResponse(response.statusCode(), durationMs, live.method(), publicUrl, headers, parsed, text,
                curl(live.method(), publicUrl, built, jsonBody, isPrivate || body.serviceAccountId() != null),
                target.accountName());
    }

    private record Built(String pathAndQuery, Map<String, String> headers, ObjectNode body) {
    }

    private Built build(String ws, EndpointVersion.Snapshot live, TryRequest req) {
        Map<String, Object> values = req.params() == null ? Map.of() : req.params();
        String path = live.path();
        List<String> query = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        ObjectNode body = null;
        for (EndpointDefinition.Param p : live.definition().params()) {
            Object value = values.get(p.name());
            if (value == null || (value instanceof String s && s.isEmpty())) {
                continue;
            }
            switch (p.in()) {
                case path -> path = path.replace("{" + p.name() + "}", enc(String.valueOf(value)));
                case query -> {
                    if (value instanceof List<?> list) {
                        list.forEach(v -> query.add(enc(p.name()) + "=" + enc(String.valueOf(v))));
                    } else {
                        query.add(enc(p.name()) + "=" + enc(String.valueOf(value)));
                    }
                }
                case header -> headers.put(p.name(), String.valueOf(value));
                case body -> {
                    if (body == null) {
                        body = json.createObjectNode();
                    }
                    body.set(p.name(), json.valueToTree(value));
                }
            }
        }
        if (path.contains("{")) {
            throw ApiProblem.badRequest("invalid_param", "Give a value for every path parameter.");
        }
        if (live.definition().params().stream().anyMatch(p -> p.in() == ParamIn.body) && body == null) {
            body = json.createObjectNode();
        }
        if (req.page() != null) {
            query.add("page=" + req.page());
        }
        if (req.size() != null) {
            query.add("size=" + req.size());
        }
        String pathAndQuery = "/" + properties.serving().context() + "/" + ws + "/" + path
                + (query.isEmpty() ? "" : "?" + String.join("&", query));
        return new Built(pathAndQuery, headers, body);
    }

    private static String curl(String method, String url, Built built, String jsonBody, boolean withToken) {
        StringBuilder sb = new StringBuilder("curl");
        if (!"GET".equals(method)) {
            sb.append(" -X ").append(method);
        }
        sb.append(" '").append(url.replace("'", "'\\''")).append("'");
        if (withToken) {
            sb.append(" \\\n  -H \"Authorization: Bearer $TOKEN\"");
        }
        built.headers().forEach((k, v) -> sb.append(" \\\n  -H '").append(k).append(": ").append(v.replace("'", "'\\''")).append("'"));
        if (jsonBody != null) {
            sb.append(" \\\n  -H 'Content-Type: application/json' -d '").append(jsonBody.replace("'", "'\\''")).append("'");
        }
        return sb.toString();
    }

    private String loopbackBase() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String address = environment.getProperty("server.address", "127.0.0.1");
        if ("0.0.0.0".equals(address) || "::".equals(address)) {
            address = "127.0.0.1";
        }
        String host = address.contains(":") ? "[" + address + "]" : address;
        return "http://" + host + ":" + port + environment.getProperty("server.servlet.context-path", "");
    }

    private String publicBase() {
        String base = properties.serving().urlBase();
        if (base.isBlank()) {
            base = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
