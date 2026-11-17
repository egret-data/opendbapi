package io.opendbapi.core.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.SecretResolver;
import io.opendbapi.core.workspace.WorkspaceScope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells a workspace's alert channels when an endpoint with alerts on fails (spec §13.2).
 * Delivery is asynchronous and best effort: a slow or broken webhook never slows a call.
 * Repeats of the same failure (endpoint and error code) within the window are counted and
 * reported with the next alert instead of sent one by one. Alerts carry no parameter values
 * and no data.
 */
@Component
public class Alerts {

    private static final Logger log = LoggerFactory.getLogger(Alerts.class);
    public static final List<String> KINDS = List.of("slack", "teams", "google_chat", "webhook");

    /** One failed call, as much as an alert may tell. */
    public record Failure(UUID workspaceId, String workspace, UUID endpointId, int version, String method, String path,
                          int status, String code, String requestId, Instant at) {
    }

    /** Outcome of one delivery attempt. */
    public record Delivery(boolean ok, Integer status, String error) {
    }

    private record Window(long startNanos, int suppressed) {
    }

    private final WorkspaceScope scope;
    private final AlertChannelStore store;
    private final SecretResolver secrets;
    private final ObjectMapper json;
    private final boolean allowHttp;
    private final Duration dedup;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
            Thread.ofVirtual().name("alert-", 0).factory(), new ThreadPoolExecutor.DiscardPolicy());
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets;

    @org.springframework.beans.factory.annotation.Autowired
    void setWorkspaceSecrets(io.opendbapi.core.secret.WorkspaceSecrets workspaceSecrets) {
        this.workspaceSecrets = workspaceSecrets;
    }

    /**
     * The stored reference and host for a webhook URL typed by an admin: secret:NAME (the URL is
     * read from the managed secret) or the URL itself, which gets sealed.
     */
    public String[] urlReference(UUID workspaceId, String input) {
        if (io.opendbapi.core.secret.WorkspaceSecrets.isReference(input)) {
            String ref = scope.call(workspaceId, () -> workspaceSecrets.toReference(input.strip()));
            return new String[] {ref, checkUrl(workspaceSecrets.resolve(workspaceId, ref))};
        }
        return new String[] {seal(input), checkUrl(input)};
    }

    public Alerts(WorkspaceScope scope, AlertChannelStore store, SecretResolver secrets, ObjectMapper json,
                  @Value("${opendbapi.alerts.allow-http:false}") boolean allowHttp,
                  @Value("${opendbapi.alerts.dedup-window:PT5M}") Duration dedup) {
        this.scope = scope;
        this.store = store;
        this.secrets = secrets;
        this.json = json;
        this.allowHttp = allowHttp;
        this.dedup = dedup;
    }

    /**
     * Checks a webhook URL and returns its host. HTTPS only (plain HTTP when
     * {@code opendbapi.alerts.allow-http}); redirects are never followed.
     */
    public String checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("invalid_url", "That is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw ApiProblem.badRequest("invalid_url", "The webhook URL must start with https://.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiProblem.badRequest("invalid_url", "The webhook URL needs a host.");
        }
        if (uri.getUserInfo() != null) {
            throw ApiProblem.badRequest("invalid_url", "Put no user name or password in the webhook URL.");
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Seals a webhook URL for storage; needs the master key. */
    public String seal(String url) {
        if (!secrets.canSeal()) {
            throw ApiProblem.conflict("master_key_required",
                    "Webhook URLs are stored encrypted; set OPENDBAPI_MASTER_KEY on the server first.");
        }
        return secrets.seal(url.strip());
    }

    /** Called on the serving path; returns at once. */
    public void endpointFailed(Failure f) {
        String key = f.workspaceId() + "|" + f.endpointId() + "|" + f.code();
        long now = System.nanoTime();
        int[] suppressed = {-1};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startNanos() >= dedup.toNanos()) {
                suppressed[0] = w == null ? 0 : w.suppressed();
                return new Window(now, 0);
            }
            return new Window(w.startNanos(), w.suppressed() + 1);
        });
        if (suppressed[0] < 0) {
            return; // a repeat inside the window
        }
        int repeats = suppressed[0];
        sender.execute(() -> deliverAll(f, repeats));
    }

    private void deliverAll(Failure f, int repeats) {
        try {
            List<AlertChannelStore.ChannelTarget> targets = scope.call(f.workspaceId(), store::enabledTargets);
            for (AlertChannelStore.ChannelTarget t : targets) {
                Delivery d = send(f.workspaceId(), t, text(f, repeats), event(f, repeats));
                scope.run(f.workspaceId(), () -> store.delivered(t.id(), d.ok() ? null : d.error()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not deliver alert for endpoint {}: {}", f.endpointId(), e.getMessage());
        }
    }

    /** Sends a test message to one channel, synchronously. */
    public Delivery test(UUID workspaceId, AlertChannelStore.ChannelTarget t, String workspace) {
        Delivery d = send(workspaceId, t, "OpenDBAPI test alert for workspace " + workspace + ": this channel works.",
                json.createObjectNode().put("event", "test").put("workspace", workspace).put("at", Instant.now().toString()));
        scope.run(workspaceId, () -> store.delivered(t.id(), d.ok() ? null : d.error()));
        return d;
    }

    private Delivery send(UUID workspaceId, AlertChannelStore.ChannelTarget t, String text, ObjectNode event) {
        String url;
        try {
            url = workspaceSecrets.resolve(workspaceId, t.urlRef());
        } catch (RuntimeException e) {
            return new Delivery(false, null, "The webhook URL cannot be read: " + e.getMessage());
        }
        ObjectNode body = "webhook".equals(t.kind()) ? event : json.createObjectNode().put("text", text);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("User-Agent", "OpenDBAPI-alerts")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status / 100 == 2 ? new Delivery(true, status, null)
                    : new Delivery(false, status, "The webhook answered HTTP " + status + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(false, null, "Interrupted.");
        } catch (Exception e) {
            return new Delivery(false, null, "Could not reach the webhook: " + e.getClass().getSimpleName());
        }
    }

    static String text(Failure f, int repeats) {
        String more = repeats > 0 ? " (%d more like it since the last alert)".formatted(repeats) : "";
        return "OpenDBAPI: %s /%s in workspace %s failed with %d %s at %s. Request id %s.%s".formatted(f.method(),
                f.path().startsWith("/") ? f.path().substring(1) : f.path(), f.workspace(), f.status(), f.code(), f.at(),
                f.requestId(), more);
    }

    private ObjectNode event(Failure f, int repeats) {
        ObjectNode e = json.createObjectNode().put("event", "endpoint_failed").put("workspace", f.workspace())
                .put("status", f.status()).put("code", f.code()).put("requestId", f.requestId()).put("at", f.at().toString())
                .put("repeatsSinceLastAlert", repeats);
        e.putObject("endpoint").put("id", f.endpointId().toString()).put("version", f.version()).put("method", f.method())
                .put("path", f.path());
        return e;
    }
}
