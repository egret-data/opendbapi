package io.opendbapi.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opendbapi.core.client.ServiceAccountStore.ClientSecurity;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.secret.SecretResolver;
import io.opendbapi.core.workspace.WorkspaceScope;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Per-client request signatures (spec §8.2) and response encryption (spec §8.3). Nonces are
 * remembered for 10 minutes on this instance, so a signed request cannot be replayed to it.
 */
@Component
public class RequestSecurity {

    public static final String TIMESTAMP = "X-Timestamp";
    public static final String NONCE = "X-Nonce";
    public static final String SIGNATURE = "X-Signature";
    private static final long MAX_SKEW_MS = Duration.ofMinutes(5).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceScope scope;
    private final ServiceAccountStore store;
    private final SecretResolver secrets;
    private final ObjectMapper json;
    private final Cache<String, Boolean> nonces = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(1_000_000).build();
    private final Cache<String, ClientSecurity> settings = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(100_000).build();

    public RequestSecurity(WorkspaceScope scope, ServiceAccountStore store, SecretResolver secrets, ObjectMapper json) {
        this.scope = scope;
        this.store = store;
        this.secrets = secrets;
        this.json = json;
    }

    public ClientSecurity settings(UUID workspaceId, String clientId) {
        return settings.get(clientId, id -> scope.call(workspaceId, () -> store.security(id)));
    }

    /** Settings changed through this instance: use them at once. */
    public void forget(String clientId) {
        settings.invalidate(clientId);
    }

    /** A fresh signing key, as given to the client once. */
    public static String newSigningKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return "odh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /**
     * The string a client signs: METHOD, path, sorted query, SHA-256 of the body (hex),
     * timestamp and nonce, one per line.
     */
    public static String canonical(String method, String path, Map<String, List<String>> query, byte[] body, String timestamp,
                                   String nonce) {
        List<String> pairs = new ArrayList<>();
        query.forEach((name, values) -> values.forEach(v -> pairs.add(name + "=" + v)));
        pairs.sort(null);
        return String.join("\n", method, path, String.join("&", pairs), sha256Hex(body == null ? new byte[0] : body), timestamp, nonce);
    }

    /** @throws ApiProblem 401 {@code bad_signature} */
    public void verify(String clientId, ClientSecurity s, String method, String path, Map<String, List<String>> query, byte[] body,
                       Map<String, String> headers, long nowMillis) {
        String timestamp = headers.get(TIMESTAMP.toLowerCase());
        String nonce = headers.get(NONCE.toLowerCase());
        String signature = headers.get(SIGNATURE.toLowerCase());
        if (timestamp == null || nonce == null || signature == null) {
            throw bad("This client must sign requests: send X-Timestamp, X-Nonce and X-Signature.");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            throw bad("X-Timestamp must be milliseconds since the epoch.");
        }
        if (Math.abs(nowMillis - ts) > MAX_SKEW_MS) {
            throw bad("X-Timestamp is more than 5 minutes off the server's clock.");
        }
        if (nonce.isBlank() || nonce.length() > 128) {
            throw bad("X-Nonce must be 1 to 128 characters.");
        }
        byte[] expected = hmac(secrets.resolve(s.hmacKeyRef()), canonical(method, path, query, body, timestamp.strip(), nonce));
        byte[] given;
        try {
            given = Base64.getDecoder().decode(signature.strip());
        } catch (IllegalArgumentException e) {
            throw bad("X-Signature must be base64.");
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw bad("The signature does not match the request.");
        }
        // Only a correctly signed request uses up its nonce.
        if (nonces.asMap().putIfAbsent(clientId + "\n" + nonce, Boolean.TRUE) != null) {
            throw bad("This nonce was already used.");
        }
    }

    public static byte[] hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts a response body: AES-256-GCM with a fresh key, the key wrapped with the client's
     * RSA key (OAEP, SHA-256). Returns {@code {encryptedKey, iv, data}}, all base64.
     */
    public ObjectNode encrypt(String publicKeyPem, byte[] plaintext) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey aes = generator.generateKey();
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] data = gcm.doFinal(plaintext);
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey(publicKeyPem),
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] wrapped = rsa.doFinal(aes.getEncoded());
            Base64.Encoder b64 = Base64.getEncoder();
            return json.createObjectNode().put("encryptedKey", b64.encodeToString(wrapped)).put("iv", b64.encodeToString(iv))
                    .put("data", b64.encodeToString(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the response", e);
        }
    }

    /** @throws ApiProblem 400 unless the PEM is an RSA public key of at least 2048 bits */
    public static PublicKey publicKey(String pem) {
        try {
            String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (((RSAPublicKey) key).getModulus().bitLength() < 2048) {
                throw ApiProblem.badRequest("invalid_public_key", "The RSA key must have at least 2048 bits.");
            }
            return key;
        } catch (ApiProblem e) {
            throw e;
        } catch (Exception e) {
            throw ApiProblem.badRequest("invalid_public_key", "Give an RSA public key in PEM form (-----BEGIN PUBLIC KEY-----).");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiProblem bad(String detail) {
        return new ApiProblem(401, "bad_signature", detail);
    }

    /** For tests and client libraries: the signature header value. */
    public static String sign(String key, String canonical) {
        return Base64.getEncoder().encodeToString(hmac(key, canonical));
    }
}
