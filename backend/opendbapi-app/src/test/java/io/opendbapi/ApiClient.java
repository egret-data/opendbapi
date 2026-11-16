package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Talks to the app the way the SPA does: cookies kept per client, the {@code XSRF-TOKEN}
 * cookie echoed as {@code X-XSRF-TOKEN} on mutating requests.
 */
public class ApiClient {

    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public record Response(int status, String body) {
        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("Not JSON: " + body, e);
            }
        }

        public Response expect(int expected) {
            assertThat(status).as("HTTP status; body: %s", body).isEqualTo(expected);
            return this;
        }

        public Response expectProblem(int expected, String code) {
            expect(expected);
            assertThat(json().path("code").asText()).as("problem code; body: %s", body).isEqualTo(code);
            return this;
        }
    }

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private boolean sendCsrf = true;

    public ApiClient(String base) {
        this.base = base;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ApiClient withoutCsrf() {
        this.sendCsrf = false;
        return this;
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    public Response login(String username, String password) {
        return post("/admin/api/auth/login", Map.of("source", "local", "username", username, "password", password));
    }

    public Response ldapLogin(String idpId, String username, String password) {
        return post("/admin/api/auth/login",
                Map.of("source", "ldap", "idpId", idpId, "username", username, "password", password));
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private Response send(String method, String path, Object body) {
        try {
            if (!"GET".equals(method) && sendCsrf && cookie("XSRF-TOKEN") == null) {
                get("/admin/api/auth/providers"); // first visit issues the CSRF cookie
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (!"GET".equals(method) && sendCsrf && cookie("XSRF-TOKEN") != null) {
                request.header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"));
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body));
            HttpResponse<String> response = http.send(request.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
