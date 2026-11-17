package io.opendbapi.serving;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.client.AccessTokens;
import io.opendbapi.core.client.CallerAuthorizer;
import io.opendbapi.core.client.TryItRelay;
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
    private final TryItRelay relay;
    private final io.opendbapi.core.activity.AccessLogWriter accessLog;
    private final io.opendbapi.core.datapolicy.DataPolicies dataPolicies;
    private final io.opendbapi.core.alert.Alerts alerts;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer;

    public ServingDispatcher(WorkspaceStore workspaces, EndpointRegistry registry, WorkspaceScope scope,
                             DatasourceStore datasources, QueryExecutor executor, AccessTokens tokens,
                             CallerAuthorizer authorizer, ServingGuard guard, TryItRelay relay,
                             io.opendbapi.core.activity.AccessLogWriter accessLog, io.micrometer.core.instrument.MeterRegistry meters,
                             org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                             io.opendbapi.core.datapolicy.DataPolicies dataPolicies, io.opendbapi.core.alert.Alerts alerts) {
        this.dataPolicies = dataPolicies;
        this.alerts = alerts;
        this.accessLog = accessLog;
        this.meters = meters;
        this.tracer = tracer;
        this.guard = guard;
        this.relay = relay;
        this.workspaces = workspaces;
        this.registry = registry;
        this.scope = scope;
        this.datasources = datasources;
        this.executor = executor;
        this.tokens = tokens;
        this.authorizer = authorizer;
    }

    @RequestMapping(WS_PREFIX_PATTERN + "**")
    public ResponseEntity<?> dispatch(@PathVariable("ws") String workspaceSlug, HttpServletRequest request,
                                      jakarta.servlet.http.HttpServletResponse servletResponse) {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = endpointPath(request.getRequestURI(), request.getContextPath(), workspaceSlug);
        Outcome outcome = new Outcome();
        outcome.ip = guard.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        relay.read(request.getHeader(TryItRelay.HEADER)).ifPresent(r -> {
            outcome.actorUserId = r.actorUserId().toString();
            outcome.ip = r.clientIp();
        });
        try {
            ResponseEntity<?> response = serve(workspaceSlug, method, path, request, servletResponse, requestId, outcome);
            if (response == null) { // streamed straight to the servlet response
                outcome.status = servletResponse.getStatus();
                return null;
            }
            outcome.status = response.getStatusCode().value();
            return response;
        } catch (QueryExecutor.ClientGone e) {
            outcome.status = 499;
            outcome.errorCode = "client_gone";
            return null;
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
            outcome.error = e.getMessage();
            if (servletResponse.isCommitted()) { // mid-stream: the writer already said so in-band
                outcome.status = servletResponse.getStatus();
                outcome.errorCode = "query_failed";
                return null;
            }
            outcome.status = 500;
            outcome.errorCode = "query_failed";
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "query_failed",
                    "The query failed. Give the endpoint's owner this request id: " + requestId + ".", requestId);
        } finally {
            record(requestId, workspaceSlug, method, path, outcome, System.nanoTime() - start);
        }
    }

    private ResponseEntity<?> serve(String slug, String method, String path, HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse servletResponse, String requestId, Outcome outcome) {
        Workspace ws = workspaces.findBySlug(slug).filter(Workspace::active).orElseThrow(() -> notFound(method, path, slug));
        outcome.workspaceId = ws.id();
        guard.checkFirewall(ws.id(), outcome.ip);
        EndpointRegistry.Match match = registry.resolve(ws.id(), method, path).orElseThrow(() -> notFound(method, path, slug));
        EndpointRegistry.Route route = match.route();
        outcome.endpointId = route.endpointId().toString();
        outcome.version = route.version();

        if (!"public".equals(route.access())) {
            authenticate(ws, route, request, outcome);
        }

        EndpointDefinition def = route.definition();
        outcome.alerts = def.alerts().enabled();
        RateLimiter.Decision rate = guard.checkRate(ws.id(), route.endpointId(), def.limits().ratePerMinute(), outcome.clientId,
                outcome.ip);
        Set<String> reserved = def.pagination().enabled() ? EndpointValidator.RESERVED : Set.of("format");
        Map<String, Object> params = ParamBinder.bind(def.params(), new ParamBinder.Request(match.pathVariables(),
                query(request), headers(request), body(request, def)), reserved);
        io.opendbapi.core.endpoint.ParamRules.enforce(def.rules(), params);
        EndpointDefinition.StreamFormat stream = streamFormat(def, request);
        QueryExecutor.PageRequest page = stream == null ? pageRequest(def.pagination(), request) : null;
        Datasource ds = scope.call(ws.id(), () -> datasources.find(route.task().datasourceId()))
                .orElseThrow(() -> new ApiProblem(503, "datasource_missing", "This endpoint's datasource is not available."));

        // Spec §12: row filter on the main statement, masks on the values.
        io.opendbapi.core.datapolicy.DataPolicies.Plan policy = dataPolicies.plan(ws.id(), route.endpointId(), ds.id(),
                outcome.serviceAccountId == null ? null : UUID.fromString(outcome.serviceAccountId));

        if (stream != null) {
            streamTo(servletResponse, stream, ds, route, params, def, rate, requestId, outcome, policy);
            return null;
        }

        QueryExecutor.QueryResult result = executor.run(ds, route.task(), params, page, def.limits(), QueryExecutor.RunMode.SERVE,
                policy::rewriteMain);
        io.opendbapi.core.endpoint.ResponseBody.Parts parts = io.opendbapi.core.endpoint.ResponseBody.build(def, result, page,
                r -> io.opendbapi.core.datapolicy.DataPolicies.Plan.apply(policy.masksFor(r.columns()), r.columns(), r.rows()));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", route.version());
        meta.put("cached", false);
        meta.putAll(parts.pageMeta());
        meta.put("requestId", requestId);
        outcome.rows = parts.rows();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", parts.data());
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
        if (TryItRelay.isTryItClient(caller.clientId()) && outcome.actorUserId == null) {
            throw new ApiProblem(401, "invalid_token", "This token is only valid for Try-it.");
        }
        CallerStatus status = authorizer.check(ws.id(), caller, route.endpointId());
        if (status == CallerStatus.INVALID) {
            throw new ApiProblem(401, "invalid_token", "The client was revoked or its service account disabled.");
        }
        if (status == CallerStatus.NOT_GRANTED) {
            throw new ApiProblem(403, "not_granted", "This client has not been granted access to the endpoint.");
        }
    }

    /** Access log (file line and table), request metrics (spec §18–19). */
    private void record(String requestId, String workspaceSlug, String method, String path, Outcome outcome, long nanos) {
        long ms = nanos / 1_000_000;
        String traceId = null;
        io.micrometer.tracing.Tracer t = tracer.getIfAvailable();
        if (t != null && t.currentSpan() != null) {
            traceId = t.currentSpan().context().traceId();
        }
        access.info("{\"requestId\":\"{}\",\"ws\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"endpointId\":{},\"version\":{},"
                        + "\"status\":{},\"durationMs\":{},\"rows\":{},\"error\":{},\"clientId\":{},\"actorUserId\":{},"
                        + "\"traceId\":{},\"ip\":\"{}\"}",
                requestId, workspaceSlug, method, path, quote(outcome.endpointId), outcome.version, outcome.status, ms,
                outcome.rows, quote(outcome.errorCode), quote(outcome.clientId), quote(outcome.actorUserId), quote(traceId),
                outcome.ip);
        String client = outcome.actorUserId != null ? "try-it" : outcome.clientId == null ? "anonymous" : outcome.clientId;
        String endpoint = outcome.endpointId == null ? "none" : outcome.endpointId;
        String version = outcome.version == null ? "none" : outcome.version.toString();
        io.micrometer.core.instrument.Counter.builder("opendbapi.requests").description("Calls to published endpoints")
                .tags("endpoint", endpoint, "version", version, "status", Integer.toString(outcome.status), "client", client)
                .register(meters).increment();
        io.micrometer.core.instrument.Timer.builder("opendbapi.request.duration").description("Time to answer a call")
                .tags("endpoint", endpoint, "status", Integer.toString(outcome.status / 100) + "xx")
                .register(meters).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        // Spec §13.2: server-side failures of endpoints with alerts on; Try-it calls are authors testing.
        if (outcome.alerts && outcome.status >= 500 && outcome.actorUserId == null && outcome.endpointId != null) {
            alerts.endpointFailed(new io.opendbapi.core.alert.Alerts.Failure(outcome.workspaceId, workspaceSlug,
                    UUID.fromString(outcome.endpointId), outcome.version == null ? 0 : outcome.version, method, path, outcome.status,
                    outcome.errorCode == null ? "error" : outcome.errorCode, requestId, java.time.Instant.now()));
        }
        if (outcome.workspaceId != null) {
            accessLog.offer(new io.opendbapi.core.activity.AccessRecord(java.time.Instant.now(), outcome.workspaceId,
                    outcome.endpointId == null ? null : UUID.fromString(outcome.endpointId), outcome.version, method, path,
                    outcome.status, ms, outcome.rows, outcome.clientId,
                    outcome.serviceAccountId == null ? null : UUID.fromString(outcome.serviceAccountId), outcome.ip,
                    outcome.errorCode, outcome.error, traceId,
                    outcome.actorUserId == null ? null : UUID.fromString(outcome.actorUserId), requestId));
        }
    }

    /** Spec §9.6: {@code ?format=ndjson|csv} or an Accept header asking for one of them. */
    private static EndpointDefinition.StreamFormat streamFormat(EndpointDefinition def, HttpServletRequest request) {
        String format = request.getParameter("format");
        EndpointDefinition.StreamFormat wanted = null;
        if (format != null && !format.isBlank() && !"json".equalsIgnoreCase(format.strip())) {
            try {
                wanted = EndpointDefinition.StreamFormat.valueOf(format.strip().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiProblem.badRequest("invalid_param", "format must be json, ndjson or csv.");
            }
        } else if (format == null) {
            String accept = request.getHeader("Accept");
            String first = accept == null ? "" : accept.split(",")[0].split(";")[0].strip().toLowerCase(Locale.ROOT);
            if (first.equals("application/x-ndjson") || first.equals("application/ndjson")) {
                wanted = EndpointDefinition.StreamFormat.ndjson;
            } else if (first.equals("text/csv")) {
                wanted = EndpointDefinition.StreamFormat.csv;
            }
        }
        if (wanted != null && !def.streaming().allows(wanted)) {
            throw new ApiProblem(406, "not_acceptable", "This endpoint does not stream %s; ask for JSON.".formatted(wanted));
        }
        return wanted;
    }

    private void streamTo(jakarta.servlet.http.HttpServletResponse response, EndpointDefinition.StreamFormat format, Datasource ds,
                          EndpointRegistry.Route route, Map<String, Object> params, EndpointDefinition def,
                          RateLimiter.Decision rate, String requestId, Outcome outcome,
                          io.opendbapi.core.datapolicy.DataPolicies.Plan policy) {
        // Headers are set by the sink's start(), i.e. only after the query ran: an early failure
        // still becomes an ordinary problem response.
        java.io.OutputStream out = new java.io.OutputStream() {
            private java.io.OutputStream target;

            private java.io.OutputStream target() throws IOException {
                if (target == null) {
                    response.setStatus(200);
                    response.setContentType(format == EndpointDefinition.StreamFormat.csv
                            ? "text/csv; charset=utf-8" : "application/x-ndjson; charset=utf-8");
                    if (format == EndpointDefinition.StreamFormat.csv) {
                        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + route.endpointId() + ".csv\"");
                    }
                    response.setHeader("X-Request-Id", requestId);
                    response.setHeader("X-OpenDBAPI-Version", Integer.toString(route.version()));
                    rateHeaders(rate).forEach((k, v) -> v.forEach(value -> response.addHeader(k, value)));
                    target = response.getOutputStream();
                }
                return target;
            }

            @Override
            public void write(int b) throws IOException {
                target().write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                target().write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                target().flush();
            }
        };
        QueryExecutor.RowSink sink = format == EndpointDefinition.StreamFormat.csv
                ? StreamWriters.csv(out, RequestJson.MAPPER) : StreamWriters.ndjson(out, RequestJson.MAPPER, requestId);
        QueryExecutor.RowSink committing = new QueryExecutor.RowSink() {
            private io.opendbapi.sql.policy.Masks.Masker[] masks;
            private List<String> names;

            @Override
            public void start(List<QueryExecutor.ResultColumn> columns) throws IOException {
                masks = policy.masksFor(columns);
                names = columns.stream().map(QueryExecutor.ResultColumn::name).toList();
                List<String> renamed = io.opendbapi.core.endpoint.ResultMapper.streamNames(def.output(), names);
                List<QueryExecutor.ResultColumn> shown = new java.util.ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    shown.add(new QueryExecutor.ResultColumn(renamed.get(i), columns.get(i).type()));
                }
                sink.start(shown);
                out.flush(); // commits status and headers once the query has run
            }

            @Override
            public void row(Object[] values) throws IOException {
                io.opendbapi.core.datapolicy.DataPolicies.Plan.apply(masks, values);
                io.opendbapi.core.endpoint.ResultMapper.streamValues(def.output(), names, values);
                sink.row(values);
            }

            @Override
            public void end(boolean truncated) throws IOException {
                sink.end(truncated);
                outcome.truncated = truncated;
            }

            @Override
            public void failed(String code) throws IOException {
                sink.failed(code);
            }
        };
        outcome.rows = executor.stream(ds, route.task(), params, def.limits(), committing, policy::rewriteMain);
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
        if (p.keyset()) {
            int size = intParam(request, "size", p.defaultSize());
            if (size < 1 || size > p.maxSize()) {
                throw ApiProblem.badRequest("invalid_param", "size must be between 1 and %d.".formatted(p.maxSize()));
            }
            String after = request.getParameter("after");
            return new QueryExecutor.PageRequest(1, size, false, p.keys(),
                    after == null || after.isBlank() ? null : io.opendbapi.core.endpoint.KeysetCursor.decode(after, p.keys().size()));
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
        String actorUserId;
        boolean truncated;
        UUID workspaceId;
        String error;
        boolean alerts;
    }
}
