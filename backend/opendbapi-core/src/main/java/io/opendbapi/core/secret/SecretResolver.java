package io.opendbapi.core.secret;

import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.error.ApiProblem;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Turns a stored secret reference into the secret (spec §4.5). Secrets are never stored in
 * plain text; a reference is one of:
 * <ul>
 *   <li>{@code env:NAME} — read from the environment (or any Spring property source) at use time;
 *   <li>{@code enc:v1:<base64>} — AES-256-GCM sealed with {@code opendbapi.master-key}.
 * </ul>
 */
@Component
public class SecretResolver {

    private static final String ENV = "env:";
    private static final String ENC_V1 = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Environment environment;
    private final SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretResolver(Environment environment, OpenDbApiProperties properties) {
        this.environment = environment;
        this.masterKey = parseKey(properties.masterKey());
    }

    /** The secret behind {@code ref}; {@code null} when there is no reference. */
    public String resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith(ENV)) {
            String name = ref.substring(ENV.length());
            String value = environment.getProperty(name);
            if (value == null) {
                throw ApiProblem.badRequest("secret_unavailable",
                        "Secret reference env:%s is not set in the server's environment.".formatted(name));
            }
            return value;
        }
        if (ref.startsWith(ENC_V1)) {
            return decrypt(ref.substring(ENC_V1.length()));
        }
        throw ApiProblem.badRequest("secret_reference_invalid",
                "A secret reference must start with env: or enc:v1:.");
    }

    /** Whether a master key is configured, so {@link #seal} works. */
    public boolean canSeal() {
        return masterKey != null;
    }

    /** Seals a plain-text secret into an {@code enc:v1:} reference. */
    public String seal(String plaintext) {
        SecretKey key = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return ENC_V1 + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal secret", e);
        }
    }

    /** Accepts either a reference ({@code env:}/{@code enc:v1:}) or plain text, which gets sealed. */
    public String toReference(String referenceOrPlaintext) {
        if (referenceOrPlaintext == null || referenceOrPlaintext.isEmpty()) {
            return null;
        }
        if (referenceOrPlaintext.startsWith(ENV) || referenceOrPlaintext.startsWith(ENC_V1)) {
            return referenceOrPlaintext;
        }
        return seal(referenceOrPlaintext);
    }

    private String decrypt(String encoded) {
        SecretKey key = requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiProblem.badRequest("secret_unreadable",
                    "A stored secret cannot be decrypted; it was sealed with a different master key or is corrupt.");
        }
    }

    private SecretKey requireKey() {
        if (masterKey == null) {
            throw ApiProblem.badRequest("master_key_missing",
                    "Storing secrets needs opendbapi.master-key (OPENDBAPI_MASTER_KEY); use an env: reference instead, or ask the operator to set it.");
        }
        return masterKey;
    }

    private static SecretKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("opendbapi.master-key must be base64 of exactly 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
