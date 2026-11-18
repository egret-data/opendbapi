package io.opendbapi.core.datasource;

import io.opendbapi.core.error.ApiProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The non-secret settings of an HTTP datasource, stored as {@code datasource.config} (spec §4.6).
 * The token, password or API key is the datasource's secret reference.
 *
 * @param baseUrl          {@code http(s)://host[:port][/path]}, no credentials, query or fragment
 * @param auth             {@code none}, {@code bearer} (Authorization: Bearer secret), {@code basic}
 *                         (username and secret) or {@code header} (the secret in {@code headerName})
 * @param headers          sent with every request; not secret, so never credentials
 * @param connectTimeoutMs how long to wait for a connection
 */
public record HttpConfig(
        String baseUrl,
        @Schema(allowableValues = {"none", "bearer", "basic", "header"}) String auth,
        @Schema(nullable = true, description = "Basic authentication only") String username,
        @Schema(nullable = true, description = "API key authentication only") String headerName,
        @Schema(nullable = true) Map<String, String> headers,
        @Schema(nullable = true, description = "250–60000 ms, default 10000") Integer connectTimeoutMs) {

    public static final Set<String> AUTH = Set.of("none", "bearer", "basic", "header");
    public static final int MAX_CONNECT_TIMEOUT_MS = 60_000;

    /** Headers the HTTP client sets itself, or that would let static headers smuggle credentials or routing. */
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding",
            "expect", "upgrade", "te", "trailer", "authorization", "proxy-authorization", "cookie", "keep-alive");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}$");
    private static final Pattern HEADER_VALUE = Pattern.compile("^[\\x20-\\x7E]{0,500}$");

    public HttpConfig {
        auth = auth == null || auth.isBlank() ? "none" : auth.toLowerCase(Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        connectTimeoutMs = connectTimeoutMs == null ? 10_000 : connectTimeoutMs;
        baseUrl = baseUrl == null ? null : baseUrl.strip().replaceAll("/+$", "");
    }

    /** @throws ApiProblem (400) naming the first invalid field */
    public HttpConfig validate() {
        URI uri = uri();
        if (uri.getRawUserInfo() != null) {
            throw invalid("baseUrl must not contain a user name or password; use the authentication settings.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("baseUrl must not have a query (?…) or fragment (#…).");
        }
        if (!AUTH.contains(auth)) {
            throw invalid("auth must be none, bearer, basic or header.");
        }
        if ("basic".equals(auth) && (username == null || username.isBlank() || username.contains(":"))) {
            throw invalid("Basic authentication needs a username without ':'.");
        }
        if ("header".equals(auth) && (headerName == null || !HEADER_NAME.matcher(headerName).matches()
                || RESERVED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT)) && !"authorization".equalsIgnoreCase(headerName))) {
            throw invalid("API key authentication needs a header name, e.g. X-API-Key.");
        }
        for (var e : headers.entrySet()) {
            if (!HEADER_NAME.matcher(e.getKey()).matches() || RESERVED_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                throw invalid("Header '%s' cannot be set here%s.".formatted(e.getKey(),
                        "authorization".equalsIgnoreCase(e.getKey()) ? "; use the authentication settings, which keep it secret" : ""));
            }
            if (e.getValue() == null || !HEADER_VALUE.matcher(e.getValue()).matches()) {
                throw invalid("Header '%s' has characters that are not allowed.".formatted(e.getKey()));
            }
        }
        if (connectTimeoutMs < 250 || connectTimeoutMs > MAX_CONNECT_TIMEOUT_MS) {
            throw invalid("connectTimeoutMs must be between 250 and %d.".formatted(MAX_CONNECT_TIMEOUT_MS));
        }
        return this;
    }

    /** The parsed base URL; {@code http} or {@code https} with a host. */
    public URI uri() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw invalid("baseUrl is required, e.g. https://api.example.com/v1.");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null) {
                throw invalid("baseUrl must be an http:// or https:// URL with a host.");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw invalid("baseUrl is not a valid URL.");
        }
    }

    public String host() {
        return uri().getHost();
    }

    private static ApiProblem invalid(String detail) {
        return ApiProblem.badRequest("invalid_datasource", detail);
    }
}
