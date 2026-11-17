package io.opendbapi.core.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads {@code vault:PATH#KEY} references from HashiCorp Vault (spec §4.5). KV version 2 by
 * default: {@code vault:secret/opendbapi/sales/db#password} reads {@code GET /v1/secret/data/opendbapi/sales/db}
 * and returns its {@code password}.
 *
 * <p>A workspace may only read paths under {@code opendbapi.vault.allowed-paths}, where
 * {@code {workspace}} stands for its slug; otherwise one workspace's admin could have the server
 * send another workspace's secret to a host of their choosing. Server-level references (identity
 * providers) use {@code opendbapi.vault.system-paths}. Values are cached briefly; the Vault token
 * from AppRole or Kubernetes login is kept until shortly before its lease ends.
 */
@Component
public class VaultSecrets {

    public static final String PREFIX = "vault:";

    private record Cached(String value, long expiresAtNanos) {
    }

    private record Token(String value, long expiresAtNanos) {
    }

    private final String address;
    private final String auth;
    private final String staticToken;
    private final String namespace;
    private final int kvVersion;
    private final List<String> allowedPaths;
    private final List<String> systemPaths;
    private final Duration cacheFor;
    private final String loginMount;
    private final String role;
    private final String roleId;
    private final String secretId;
    private final String jwtPath;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Token token;

    public VaultSecrets(@Value("${opendbapi.vault.address:}") String address,
                        @Value("${opendbapi.vault.auth:token}") String auth,
                        @Value("${opendbapi.vault.token:}") String staticToken,
                        @Value("${opendbapi.vault.namespace:}") String namespace,
                        @Value("${opendbapi.vault.kv-version:2}") int kvVersion,
                        @Value("${opendbapi.vault.allowed-paths:secret/opendbapi/{workspace}/}") String allowedPaths,
                        @Value("${opendbapi.vault.system-paths:secret/opendbapi/system/}") String systemPaths,
                        @Value("${opendbapi.vault.cache-seconds:60}") int cacheSeconds,
                        @Value("${opendbapi.vault.login-mount:}") String loginMount,
                        @Value("${opendbapi.vault.role:}") String role,
                        @Value("${opendbapi.vault.role-id:}") String roleId,
                        @Value("${opendbapi.vault.secret-id:}") String secretId,
                        @Value("${opendbapi.vault.jwt-path:/var/run/secrets/kubernetes.io/serviceaccount/token}") String jwtPath) {
        this.address = address == null ? "" : address.strip().replaceAll("/+$", "");
        this.auth = auth.strip().toLowerCase(java.util.Locale.ROOT);
        this.staticToken = staticToken;
        this.namespace = namespace;
        this.kvVersion = kvVersion;
        this.allowedPaths = split(allowedPaths);
        this.systemPaths = split(systemPaths);
        this.cacheFor = Duration.ofSeconds(Math.max(0, cacheSeconds));
        this.loginMount = loginMount.isBlank() ? ("approle".equals(this.auth) ? "approle" : "kubernetes") : loginMount.strip();
        this.role = role;
        this.roleId = roleId;
        this.secretId = secretId;
        this.jwtPath = jwtPath;
        if (!List.of("token", "approle", "kubernetes").contains(this.auth)) {
            throw new IllegalStateException("opendbapi.vault.auth must be token, approle or kubernetes");
        }
    }

    public static boolean isReference(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    public boolean enabled() {
        return !address.isEmpty();
    }

    /** Checks a reference a workspace stores; {@code null} slug means a server-level reference. */
    public String check(String ref, String workspaceSlug) {
        Ref r = parse(ref);
        List<String> allowed = workspaceSlug == null ? systemPaths
                : allowedPaths.stream().map(p -> p.replace("{workspace}", workspaceSlug)).toList();
        if (allowed.stream().noneMatch(p -> r.path().startsWith(p))) {
            throw ApiProblem.badRequest("secret_reference_invalid", "Vault path %s is outside what %s may read (%s)."
                    .formatted(r.path(), workspaceSlug == null ? "the server" : "this workspace", String.join(", ", allowed)));
        }
        return PREFIX + r.path() + "#" + r.key();
    }

    /** The value behind a reference already passed through {@link #check}. */
    public String read(String ref, String workspaceSlug) {
        String checked = check(ref, workspaceSlug);
        Cached hit = cache.get(checked);
        long now = System.nanoTime();
        if (hit != null && hit.expiresAtNanos() - now > 0) {
            return hit.value();
        }
        Ref r = parse(checked);
        JsonNode data = get(r.path());
        JsonNode value = (kvVersion == 2 ? data.path("data").path("data") : data.path("data")).get(r.key());
        if (value == null || value.isNull()) {
            throw unavailable("Vault secret %s has no key %s.".formatted(r.path(), r.key()));
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        if (!cacheFor.isZero()) {
            cache.put(checked, new Cached(text, now + cacheFor.toNanos()));
        }
        return text;
    }

    /** Forgets cached values, e.g. after a secret was rotated in Vault. */
    public void forget() {
        cache.clear();
    }

    // ------------------------------------------------------------------ internals

    private record Ref(String path, String key) {
    }

    private Ref parse(String ref) {
        if (!enabled()) {
            throw ApiProblem.badRequest("secret_reference_invalid", "Vault references need opendbapi.vault.address on the server.");
        }
        String body = ref.substring(PREFIX.length()).strip();
        int hash = body.lastIndexOf('#');
        String path = hash < 0 ? "" : body.substring(0, hash).replaceAll("^/+|/+$", "");
        String key = hash < 0 ? "" : body.substring(hash + 1);
        if (path.isEmpty() || key.isEmpty() || path.contains("..") || !path.matches("[A-Za-z0-9_./\\-]+")) {
            throw ApiProblem.badRequest("secret_reference_invalid", "A Vault reference looks like vault:secret/path/to/secret#key.");
        }
        return new Ref(path, key);
    }

    private JsonNode get(String path) {
        String apiPath = path;
        if (kvVersion == 2) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                throw ApiProblem.badRequest("secret_reference_invalid", "A KV v2 path starts with its mount, e.g. secret/app/db.");
            }
            apiPath = path.substring(0, slash) + "/data/" + path.substring(slash + 1);
        }
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(address + "/v1/" + apiPath)).GET(), true);
        if (r.statusCode() == 403 && !"token".equals(auth)) {
            token = null;
            r = send(HttpRequest.newBuilder(URI.create(address + "/v1/" + apiPath)).GET(), true);
        }
        if (r.statusCode() == 404) {
            throw unavailable("Vault has no secret at " + path + ".");
        }
        if (r.statusCode() != 200) {
            throw unavailable("Vault answered %d for %s.".formatted(r.statusCode(), path));
        }
        return readTree(r.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request, boolean authenticated) {
        if (!namespace.isBlank()) {
            request.header("X-Vault-Namespace", namespace);
        }
        if (authenticated) {
            request.header("X-Vault-Token", token());
        }
        try {
            return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable("Vault is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while reading from Vault.");
        }
    }

    private String token() {
        if ("token".equals(auth)) {
            if (staticToken == null || staticToken.isBlank()) {
                throw unavailable("opendbapi.vault.token is not set.");
            }
            return staticToken;
        }
        Token t = token;
        if (t != null && t.expiresAtNanos() - System.nanoTime() > 0) {
            return t.value();
        }
        synchronized (this) {
            t = token;
            if (t != null && t.expiresAtNanos() - System.nanoTime() > 0) {
                return t.value();
            }
            String body;
            if ("approle".equals(auth)) {
                body = "{\"role_id\":" + quote(roleId) + ",\"secret_id\":" + quote(secretId) + "}";
            } else {
                String jwt;
                try {
                    jwt = Files.readString(Path.of(jwtPath)).strip();
                } catch (IOException e) {
                    throw unavailable("Cannot read the Kubernetes service account token at " + jwtPath + ".");
                }
                body = "{\"role\":" + quote(role) + ",\"jwt\":" + quote(jwt) + "}";
            }
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(address + "/v1/auth/"
                    + URLEncoder.encode(loginMount, StandardCharsets.UTF_8).replace("%2F", "/") + "/login"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)), false);
            if (r.statusCode() != 200) {
                throw unavailable("Vault login (%s) failed with %d.".formatted(auth, r.statusCode()));
            }
            JsonNode a = readTree(r.body()).path("auth");
            long lease = Math.max(10, a.path("lease_duration").asLong(300));
            token = new Token(a.path("client_token").asText(), System.nanoTime() + Duration.ofSeconds(lease * 9 / 10).toNanos());
            return token.value();
        }
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw unavailable("Vault answered with unreadable JSON.");
        }
    }

    private String quote(String s) {
        try {
            return json.writeValueAsString(s == null ? "" : s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("^/+", "")).toList();
    }

    private static ApiProblem unavailable(String message) {
        return ApiProblem.badRequest("secret_unavailable", message);
    }
}
