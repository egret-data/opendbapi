package io.opendbapi.core.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Client ids and secrets (spec §8.1). Secrets are 256 random bits, so a fast SHA-256 is enough
 * to store them: there is nothing to brute-force that a slow hash would protect.
 */
public final class ClientSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientSecrets() {
    }

    public static String newClientId() {
        return "odc_" + random(12);
    }

    public static String newSecret() {
        return "ods_" + random(32);
    }

    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String secret, String storedHash) {
        if (secret == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
