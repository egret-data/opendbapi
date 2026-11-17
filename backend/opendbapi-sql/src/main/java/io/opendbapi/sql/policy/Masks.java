package io.opendbapi.sql.policy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Column masks (spec §12.1). Null stays null; other values are masked as text. */
public final class Masks {

    public enum MaskStyle { phone, email, partial, hash, nullify }

    /**
     * @param keepStart characters kept at the start ({@code partial} only)
     * @param keepEnd   characters kept at the end ({@code partial} only)
     * @param salt      key for {@code hash}
     */
    public record Masker(MaskStyle type, int keepStart, int keepEnd, byte[] salt) {

        public Object apply(Object value) {
            if (value == null || type == MaskStyle.nullify) {
                return null;
            }
            String s = String.valueOf(value);
            return switch (type) {
                case phone -> partial(s, 3, 3);
                case email -> email(s);
                case partial -> partial(s, keepStart, keepEnd);
                case hash -> hash(s, salt);
                case nullify -> null;
            };
        }
    }

    private Masks() {
    }

    /** Keeps the ends and stars every character between; all stars when nothing would be hidden. */
    static String partial(String s, int keepStart, int keepEnd) {
        int n = s.codePointCount(0, s.length());
        if (keepStart + keepEnd >= n) {
            return "*".repeat(n);
        }
        int startEnd = s.offsetByCodePoints(0, keepStart);
        int endStart = s.offsetByCodePoints(0, n - keepEnd);
        return s.substring(0, startEnd) + "*".repeat(n - keepStart - keepEnd) + s.substring(endStart);
    }

    /** {@code nam@example.com} → {@code n***@example.com}; the length of the name is not revealed. */
    static String email(String s) {
        int at = s.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        return s.substring(0, s.offsetByCodePoints(0, 1)) + "***" + s.substring(at);
    }

    /** HMAC-SHA-256 with the policy's salt: equal inputs give equal outputs, so joins still work. */
    static String hash(String s, byte[] salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
