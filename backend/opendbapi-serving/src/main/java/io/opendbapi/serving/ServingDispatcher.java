package io.opendbapi.serving;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.client.AccessTokens;
import io.opendbapi.core.client.CallerAuthorizer;
import io.opendbapi.core.client.ServiceAccountStore.CallerStatus;
import io.opendbapi.core.datasource.Datasource;
import io.opendbapi.core.datasource.DatasourceStore;
import io.opendbapi.core.endpoint.EndpointDefinition;
import io.opendbapi.core.endpoint.EndpointRegistry;
import io.opendbapi.core.endpoint.EndpointValidator;
import io.opendbapi.core.endpoint.ParamBinder;
import io.opendbapi.core.endpoint.QueryExecutor;
import io.opendbapi.core.endpoint.RequestJson;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.policy.RateLimiter;
import io.opendbapi.core.policy.ServingGuard;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.core.workspace.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The serving surface {@code /{context}/{ws}/**} (spec §3.1, §9.1): resolve the live endpoint,
 * check access, bind parameters, run the query, wrap the result. Callers never see database
 * error text; they get a request id the endpoint's owner can look up in the log.
 */
@RestController
public class ServingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ServingDispatcher.class);
    private static final Logger access = LoggerFactory.getLogger("opendbapi.access");
    private static final String WS_PREFIX_PATTERN = "/${opendbapi.serving.context:api}/{ws}/";
    private static final int MAX_BODY_BYTES = 1_048_576;

    private final WorkspaceStore workspaces;
    private final EndpointRegistry registry;
    private final WorkspaceScope scope;
    private final DatasourceStore datasources;
    private final QueryExecutor executor;
    private final AccessTokens tokens;
    private final CallerAuthorizer authorizer;
    private final ServingGuard guard;

    public ServingDispatcher(WorkspaceStore workspaces, EndpointRegistry registry, WorkspaceScope scope,
                             DatasourceStore datasources, QueryExecutor executor, AccessTokens tokens,
                             CallerAuthorizer authorizer, ServingGuard guard) {
        this.guard = guard;
        this.workspaces = workspaces;
        this.registry = registry;
        this.scope = scope;
        this.datasources = datasources;
        this.executor = executor;
        this.tokens = tokens;
        this.authorizer = authorizer;
    }

    @RequestMapping(WS_PREFIX_PATTERN + "**")
    public ResponseEntity<?> dispatch(@PathVariable("ws") String workspaceSlug, HttpServletRequest request) {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = endpointPath(request.getRequestURI(), request.getContextPath(), workspaceSlug);
        Outcome outcome = new Outcome();
        outcome.ip = guard.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        try {
            ResponseEntity<?> response = serve(workspaceSlug, method, path, request, requestId, outcome);
            outcome.status = response.getStatusCode().value();
            return response;
        } catch (ServingGuard.RateLimited e) {
            outcome.status = 429;
            outcome.errorCode = e.code();
            ResponseEntity<ProblemDetail> answer = problem(HttpStatus.TOO_MANY_REQUESTS, e.code(), e.getMessage(), requestId);
            HttpHeaders headers = rateHeaders(e.decision());
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(e.decision().retryAfterSeconds()));
            return ResponseEntity.status(429).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(answer.getBody());
        } catch (ApiProblem e) {
            outcome.status = e.status();
            outcome.errorCode = e.code();
            ResponseEntity<ProblemDetail> answer = problem(HttpStatus.valueOf(e.status()), e.code(), e.getMessage(), requestId);
            if (e.status() == 401) {
                // RFC 6750 §3: tell the caller which scheme to use and, for a bad token, why.
                String challenge = "unauthenticated".equals(e.code()) ? "Bearer realm=\"opendbapi\""
                        : "Bearer realm=\"opendbapi\", error=\"invalid_token\"";
                return ResponseEntity.status(401).header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(answer.getBody());
            }
            return answer;
        } catch (QueryExecutor.QueryFailedException e) {
            log.warn("Query failed [request {}] {} {}: {}", requestId, method, path, e.getMessage());
            outcome.status = 500;
            outcome.errorCode = "query_failed";
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "query_failed",
                    "The query failed. Give the endpoint's owner this request id: " + requestId + ".", requestId);
        } finally {
            access.info("{\"requestId\":\"{}\",\"ws\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"endpointId\":{},\"version\":{},"
                            + "\"status\":{},\"durationMs\":{},\"rows\":{},\"error\":{},\"clientId\":{},\"ip\":\"{}\"}",
                    requestId, workspaceSlug, method, path, quote(outcome.endpointId), outcome.version, outcome.status,
                    (System.nanoTime() - start) / 1_000_000, outcome.rows, quote(outcome.errorCode), quote(outcome.clientId),
                    outcome.ip);
        }
    }

    private ResponseEntity<?> serve(String slug, String method, String path, HttpServletRequest request, String requestId,
                                    Outcome outcome) {
        Workspace ws = workspaces.findBySlug(slug).filter(Workspace::active).orElseThrow(() -> notFound(method, path, slug));
        guard.checkFirewall(ws.id(), outcome.ip);
        EndpointRegistry.Match match = registry.resolve(ws.id(), method, path).orElseThrow(() -> notFound(method, path, slug));
        EndpointRegistry.Route route = match.route();
        outcome.endpointId = route.endpointId().toString();
        outcome.version = route.version();

        if (!"public".equals(route.access())) {
            authenticate(ws, route, request, outcome);
        }

        EndpointDefinition def = route.definition();
        RateLimiter.Decision rate = guard.checkRate(ws.id(), route.endpointId(), def.limits().ratePerMinute(), outcome.clientId,
                outcome.ip);
        Set<String> reserved = def.pagination().enabled() ? EndpointValidator.RESERVED : Set.of("format");
        Map<String, Object> params = ParamBinder.bind(def.params(), new ParamBinder.Request(match.pathVariables(),
                query(request), headers(request), body(request, def)), reserved);
        QueryExecutor.PageRequest page = pageRequest(def.pagination(), request);
        Datasource ds = scope.call(ws.id(), () -> datasources.find(route.task().datasourceId()))
                .orElseThrow(() -> new ApiProblem(503, "datasource_missing", "This endpoint's datasource is not available."));

        QueryExecutor.QueryResult result = executor.run(ds, route.task(), params, page, def.limits(), QueryExecutor.RunMode.SERVE);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", route.version());
        meta.put("cached", false);
        if (page != null) {
            meta.put("page", page.page());
            meta.put("size", page.size());
            if (result.total() != null) {
                meta.put("total", result.total());
            }
            meta.put("hasNext", result.hasNext());
        }
        if (result.truncated()) {
            meta.put("truncated", true);
        }
        meta.put("requestId", requestId);
        Object data = result.affectedRows() != null ? Map.of("affectedRows", result.affectedRows()) : result.rows();
        outcome.rows = result.affectedRows() != null ? result.affectedRows() : result.rows().size();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("meta", meta);
        return ResponseEntity.ok().headers(rateHeaders(rate)).contentType(MediaType.APPLICATION_JSON).body(envelope);
    }

    /** Spec §9.1 steps 4–5: a valid token for this workspace, a usable client, a grant. */
    private void authenticate(Workspace ws, EndpointRegistry.Route route, HttpServletRequest request, Outcome outcome) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            throw new ApiProblem(401, "unauthenticated", "This endpoint needs an access token: Authorization: Bearer <token>.");
        }
        AccessTokens.Caller caller = tokens.verify(header.substring(7).strip());
        outcome.clientId = caller.clientId();
        outcome.serviceAccountId = caller.serviceAccountId().toString();
        if (!caller.workspaceId().equals(ws.id())) {
            throw new ApiProblem(401, "invalid_token", "The token was issued for another workspace.");
        }
        CallerStatus status = authorizer.check(ws.id(), caller.clientId(), route.endpointId());
        if (status == CallerStatus.INVALID) {
            throw new ApiProblem(401, "invalid_token", "The client was revoked or its service account disabled.");
        }
        if (status == CallerStatus.NOT_GRANTED) {
            throw new ApiProblem(403, "not_granted", "This client has not been granted access to the endpoint.");
        }
    }

    /** IETF draft RateLimit header fields, for the tightest limit that applied. */
    private static HttpHeaders rateHeaders(RateLimiter.Decision d) {
        HttpHeaders headers = new HttpHeaders();
        if (d != null) {
            headers.set("RateLimit-Limit", Integer.toString(d.limit()));
            headers.set("RateLimit-Remaining", Integer.toString(d.remaining()));
            headers.set("RateLimit-Reset", Long.toString(d.allowed() ? Math.max(1, 60L / Math.max(1, d.limit())) : d.retryAfterSeconds()));
        }
        return headers;
    }

    private static QueryExecutor.PageRequest pageRequest(EndpointDefinition.Pagination p, HttpServletRequest request) {
        if (!p.enabled()) {
            return null;
        }
        int page = intParam(request, "page", 1);
        int size = intParam(request, "size", p.defaultSize());
        if (page < 1) {
            throw ApiProblem.badRequest("invalid_param", "page must be 1 or more.");
        }
        if (size < 1 || size > p.maxSize()) {
            throw ApiProblem.badRequest("invalid_param", "size must be between 1 and %d.".formatted(p.maxSize()));
        }
        return new QueryExecutor.PageRequest(page, size, p.count());
    }

    private static int intParam(HttpServletRequest request, String name, int fallback) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw ApiProblem.badRequest("invalid_param", "%s must be a whole number.".formatted(name));
        }
    }

    private static Map<String, List<String>> query(HttpServletRequest request) {
        Map<String, List<String>> out = new HashMap<>();
        String queryString = request.getQueryString();
        if (queryString == null) {
            return out;
        }
        // Query string only: a form body must not be mistaken for query parameters.
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return out;
    }

    private static JsonNode body(HttpServletRequest request, EndpointDefinition def) {
        boolean wantsBody = def.params().stream().anyMatch(p -> p.in() == EndpointDefinition.ParamIn.body);
        if (!wantsBody) {
            return null;
        }
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ApiProblem(415, "unsupported_media_type", "Send the body as application/json.");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiProblem(413, "body_too_large", "The body may be at most 1 MB.");
            }
            if (bytes.length == 0) {
                return RequestJson.MAPPER.createObjectNode();
            }
            JsonNode node = RequestJson.MAPPER.readTree(bytes);
            if (!node.isObject()) {
                throw ApiProblem.badRequest("invalid_body", "The body must be a JSON object.");
            }
            return node;
        } catch (IOException e) {
            throw ApiProblem.badRequest("invalid_body", "The body is not valid JSON.");
        }
    }

    /** Path after {@code /{context}/{ws}}, always starting with a slash. */
    static String endpointPath(String requestUri, String servletContextPath, String workspaceSlug) {
        String path = requestUri.substring(servletContextPath.length());
        int wsStart = path.indexOf('/', 1) + 1;
        return path.substring(wsStart + workspaceSlug.length());
    }

    private static ApiProblem notFound(String method, String path, String workspaceSlug) {
        return ApiProblem.notFound("not_found",
                "No published endpoint matches %s %s in workspace '%s'.".formatted(method, path, workspaceSlug));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, String requestId) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("about:blank"));
        problem.setDetail(detail);
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private static final class Outcome {
        int status = 500;
        String endpointId;
        Integer version;
        long rows;
        String errorCode;
        String clientId;
        String serviceAccountId;
        String ip;
    }
}
