package io.opendbapi.serving;

import com.nimbusds.jose.jwk.JWKSet;
import io.opendbapi.core.client.AccessTokens;
import io.opendbapi.core.client.ClientSecrets;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.client.SigningKeys;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.core.workspace.WorkspaceStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 token endpoint, client-credentials grant only (RFC 6749 §4.4; spec §8.1). Clients
 * authenticate with HTTP Basic or with {@code client_id}/{@code client_secret} form fields.
 * Every failure to authenticate answers the same {@code invalid_client}.
 */
@RestController
public class TokenController {

    private final ServiceAccountStore store;
    private final WorkspaceScope scope;
    private final WorkspaceStore workspaces;
    private final AccessTokens tokens;
    private final SigningKeys keys;

    public TokenController(ServiceAccountStore store, WorkspaceScope scope, WorkspaceStore workspaces, AccessTokens tokens,
                           SigningKeys keys) {
        this.store = store;
        this.scope = scope;
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.keys = keys;
    }

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestBody MultiValueMap<String, String> form, HttpServletRequest request) {
        String grantType = form.getFirst("grant_type");
        if (grantType == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "grant_type is required.");
        }
        if (!"client_credentials".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported.");
        }
        String[] credentials = basic(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (credentials == null) {
            credentials = new String[] {form.getFirst("client_id"), form.getFirst("client_secret")};
        }
        String clientId = credentials[0];
        String secret = credentials[1];
        if (clientId == null || secret == null) {
            return invalidClient();
        }
        Optional<UUID> wsId = store.workspaceOfClient(clientId);
        if (wsId.isEmpty() || workspaces.findById(wsId.get()).filter(Workspace::active).isEmpty()) {
            ClientSecrets.matches(secret, ClientSecrets.hash("timing")); // same work as a real check
            return invalidClient();
        }
        var login = scope.call(wsId.get(), () -> store.login(clientId));
        if (login.isEmpty() || !ClientSecrets.matches(secret, login.get().secretHash()) || !login.get().usable(Instant.now())) {
            return invalidClient();
        }
        scope.run(wsId.get(), () -> store.touch(clientId));
        var issued = tokens.issue(login.get().serviceAccountId(), clientId, wsId.get());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.token());
        body.put("token_type", "Bearer");
        body.put("expires_in", issued.expiresInSeconds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    @GetMapping(path = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        JWKSet set = keys.publicSet();
        return set.toJSONObject(true);
    }

    private static String[] basic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            // RFC 6749 §2.3.1: both parts are form-urlencoded before Base64.
            return new String[] {decode(decoded.substring(0, colon)), decode(decoded.substring(colon + 1))};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<Map<String, Object>> invalidClient() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"opendbapi\"")
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_client", "error_description", "Client authentication failed."));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", code, "error_description", description));
    }
}
