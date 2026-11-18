package io.opendbapi.core.datasource;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.SecretResolver;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sends requests to HTTP datasources (spec §4.6, §6). Every call checks the host against the
 * {@link HostPolicy}, never follows redirects (a redirect could lead past that check), sends
 * the datasource's credentials only to its own base URL, and reads at most
 * {@link #MAX_RESPONSE_BYTES}.
 */
@Component
public class HttpSources {

    public static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    public record Response(int status, byte[] body, String contentType) {
    }

    public record HttpTestResult(String product, String version, long latencyMs) {
    }

    private final SecretResolver secrets;
    private final HostPolicy hosts;
    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();
    private io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets;

    public HttpSources(SecretResolver secrets, HostPolicy hosts) {
        this.secrets = secrets;
        this.hosts = hosts;
    }

    /** Lets secrets be secret:NAME references (spec §4.5). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceSecrets(io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets) {
        this.workspaceSecrets = workspaceSecrets;
    }

    /**
     * @param pathAndQuery already encoded, starting with {@code /}
     * @param jsonBody     null for no body
     * @throws ApiProblem 504 {@code query_timeout} or 502 {@code upstream_failed}
     */
    public Response send(Datasource ds, String method, String pathAndQuery, byte[] jsonBody, int timeoutSeconds) {
        HttpConfig cfg = ds.http().validate();
        String secret = workspaceSecrets != null ? workspaceSecrets.resolve(ds.workspaceId(), ds.secretRef())
                : secrets.resolve(ds.secretRef());
        return send(cfg, secret, method, pathAndQuery, jsonBody, Duration.ofSeconds(timeoutSeconds));
    }

    /** Sends GET to the base URL with unsaved settings, as the datasource form's Test connection. */
    public HttpTestResult test(HttpConfig cfg, String secret) {
        cfg.validate();
        long start = System.nanoTime();
        Response r = send(cfg, secret, "GET", "", null, Duration.ofSeconds(15));
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (r.status() == 401 || r.status() == 403) {
            throw ApiProblem.badRequest("connection_failed", "Connected, but the API refused the credentials (HTTP %d).".formatted(r.status()));
        }
        if (r.status() >= 500) {
            throw ApiProblem.badRequest("connection_failed", "Connected, but the API answered HTTP %d.".formatted(r.status()));
        }
        return new HttpTestResult("HTTP " + r.status(), r.contentType() == null ? "" : r.contentType(), ms);
    }

    private Response send(HttpConfig cfg, String secret, String method, String pathAndQuery, byte[] jsonBody, Duration timeout) {
        URI uri = URI.create(cfg.baseUrl() + pathAndQuery);
        hosts.check(cfg.host());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "OpenDBAPI");
        cfg.headers().forEach(b::header);
        switch (cfg.auth()) {
            case "bearer" -> b.header("Authorization", "Bearer " + required(secret));
            case "basic" -> b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((cfg.username() + ":" + required(secret)).getBytes(StandardCharsets.UTF_8)));
            case "header" -> b.header(cfg.headerName(), required(secret));
            default -> { }
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(jsonBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<InputStream> response = client(cfg.connectTimeoutMs()).send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new ApiProblem(502, "upstream_failed", "The upstream response is larger than 10 MB.");
                }
                return new Response(response.statusCode(), body, response.headers().firstValue("Content-Type").orElse(null));
            }
        } catch (HttpConnectTimeoutException e) {
            throw new ApiProblem(502, "upstream_failed", "Could not connect to %s in time.".formatted(cfg.host()));
        } catch (HttpTimeoutException e) {
            throw new ApiProblem(504, "query_timeout", "The upstream API did not answer within %d seconds.".formatted(timeout.toSeconds()));
        } catch (IOException e) {
            throw new ApiProblem(502, "upstream_failed", "Could not reach %s: %s".formatted(cfg.host(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiProblem(503, "interrupted", "The request was interrupted.");
        }
    }

    private static String required(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw ApiProblem.badRequest("invalid_datasource", "This authentication needs a secret: a token, password or API key.");
        }
        return secret;
    }

    private HttpClient client(int connectTimeoutMs) {
        return clients.computeIfAbsent(connectTimeoutMs, ms -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ms))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }
}
