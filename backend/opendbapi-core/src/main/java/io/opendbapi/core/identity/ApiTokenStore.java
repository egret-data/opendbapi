package io.opendbapi.core.identity;

import io.opendbapi.core.error.ApiProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Personal API tokens (spec §8.6). A token is {@code odb_} plus 32 random bytes; only its
 * SHA-256 is stored, with the first characters kept to tell tokens apart.
 */
@Component
public class ApiTokenStore {

    public static final String PREFIX = "odb_";

    public record ApiToken(UUID id, String name, @Schema(description = "First characters, to recognise the token") String prefix,
                           @Schema(nullable = true, description = "Only this workspace; null means every workspace of the user")
                           String workspace,
                           Instant createdAt, @Schema(nullable = true) Instant expiresAt, @Schema(nullable = true) Instant lastUsedAt) {
    }

    /** What a valid bearer token stands for. */
    public record TokenGrant(UUID tokenId, UUID userId, UUID workspaceId) {
    }

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiToken> list(UUID userId) {
        return jdbc.sql("""
                select t.id, t.name, t.prefix, w.slug, t.created_at, t.expires_at, t.last_used_at
                from api_token t left join workspace w on w.id = t.workspace_id
                where t.user_id = ? and t.revoked_at is null order by t.created_at desc""")
                .param(userId)
                .query((rs, i) -> new ApiToken(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7))))
                .list();
    }

    /** @return the token text, shown once */
    public String create(UUID userId, String name, UUID workspaceId, Instant expiresAt) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw ApiProblem.badRequest("invalid_token", "Name the token (at most 100 characters).");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into api_token (user_id, name, prefix, token_hash, workspace_id, expires_at) values (?, ?, ?, ?, ?, ?)")
                .params(userId, name.strip(), token.substring(0, PREFIX.length() + 6), hash(token), workspaceId,
                        expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .update();
        return token;
    }

    public boolean revoke(UUID userId, UUID id) {
        return jdbc.sql("update api_token set revoked_at = now() where id = ? and user_id = ? and revoked_at is null")
                .params(id, userId).update() > 0;
    }

    /** The grant of a token that exists, is not revoked and has not expired; notes when it was used. */
    public Optional<TokenGrant> verify(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 100) {
            return Optional.empty();
        }
        Optional<TokenGrant> grant = jdbc.sql("""
                select id, user_id, workspace_id from api_token
                where token_hash = ? and revoked_at is null and (expires_at is null or expires_at > now())""")
                .param(hash(token))
                .query((rs, i) -> new TokenGrant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
                .optional();
        grant.ifPresent(g -> jdbc.sql("""
                update api_token set last_used_at = now()
                where id = ? and (last_used_at is null or last_used_at < now() - interval '1 minute')""").param(g.tokenId()).update());
        return grant;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
