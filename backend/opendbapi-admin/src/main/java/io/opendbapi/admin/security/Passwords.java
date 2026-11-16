package io.opendbapi.admin.security;

import io.opendbapi.core.error.ApiProblem;
import java.security.SecureRandom;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Hashing and the local password policy (spec §8.5). */
@Component
public class Passwords {

    public static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the user does not exist, so timing does not reveal usernames. */
    private final String decoyHash = encoder.encode("decoy-password-for-unknown-users");

    public String hash(String raw) {
        checkPolicy(raw);
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return raw != null && encoder.matches(raw, hash == null ? decoyHash : hash);
    }

    public void spendDecoyCheck(String raw) {
        encoder.matches(raw == null ? "" : raw, decoyHash);
    }

    public String generate(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    public static void checkPolicy(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH) {
            throw ApiProblem.badRequest("weak_password", "A password needs at least %d characters.".formatted(MIN_LENGTH));
        }
        if (raw.length() > MAX_LENGTH) {
            throw ApiProblem.badRequest("weak_password", "A password can have at most %d characters.".formatted(MAX_LENGTH));
        }
    }
}
