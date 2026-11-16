package io.opendbapi.core.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lets the admin API's Try-it (spec §11.2) send a real HTTP request through the serving
 * pipeline of this same process while still recording who pressed the button and from which
 * address. The header is honoured only with this process's random secret, so callers from
 * outside cannot forge it.
 */
@Component
public class TryItRelay {

    public static final String HEADER = "X-OpenDBAPI-Try-It";
    /** Prefix of the {@code cid} claim of tokens minted for Try-it: {@code tryit:<userId>}. */
    public static final String CLIENT_PREFIX = "tryit:";

    public record Relayed(UUID actorUserId, String clientIp) {
    }

    private final String secret;

    public TryItRelay() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        this.secret = HexFormat.of().formatHex(b);
    }

    public String header(UUID actorUserId, String clientIp) {
        return secret + " " + actorUserId + " " + clientIp;
    }

    public Optional<Relayed> read(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(" ");
        if (parts.length != 3 || !MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.US_ASCII),
                secret.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Relayed(UUID.fromString(parts[1]), parts[2]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isTryItClient(String clientId) {
        return clientId != null && clientId.startsWith(CLIENT_PREFIX);
    }
}
