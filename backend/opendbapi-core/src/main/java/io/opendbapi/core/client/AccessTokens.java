package io.opendbapi.core.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opendbapi.core.error.ApiProblem;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Issues and verifies caller access tokens: JWT, RS256 (spec §8.1). */
@Component
public class AccessTokens {

    public static final String ISSUER = "opendbapi";
    private static final JOSEObjectType TYPE = new JOSEObjectType("at+jwt");
    private static final Duration LEEWAY = Duration.ofSeconds(30);

    public record Issued(String token, long expiresInSeconds) {
    }

    /** A verified token: who calls, through which client, for which workspace. */
    public record Caller(UUID serviceAccountId, String clientId, UUID workspaceId, Instant expiresAt) {
    }

    private final SigningKeys keys;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public AccessTokens(SigningKeys keys, @Value("${opendbapi.tokens.ttl:PT15M}") Duration ttl) {
        this(keys, ttl, Clock.systemUTC());
    }

    AccessTokens(SigningKeys keys, Duration ttl, Clock clock) {
        this.keys = keys;
        this.ttl = ttl;
        this.clock = clock;
    }

    public Issued issue(UUID serviceAccountId, String clientId, UUID workspaceId) {
        Instant now = clock.instant();
        RSAKey key = keys.current();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(serviceAccountId.toString())
                .claim("cid", clientId)
                .claim("ws", workspaceId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(TYPE).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign access token", e);
        }
        return new Issued(jwt.serialize(), ttl.toSeconds());
    }

    /** Verifies signature, type, issuer and lifetime; throws 401 {@code invalid_token} otherwise. */
    public Caller verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || !TYPE.equals(header.getType())) {
                throw invalid("The token is not an OpenDBAPI access token.");
            }
            RSAKey key = keys.publicKey(header.getKeyID());
            if (key == null || !jwt.verify(new RSASSAVerifier(key))) {
                throw invalid("The token signature is not valid.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!ISSUER.equals(claims.getIssuer()) || claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().plus(LEEWAY).isBefore(now)) {
                throw invalid("The token has expired. Request a new one.");
            }
            return new Caller(UUID.fromString(claims.getSubject()), claims.getStringClaim("cid"),
                    UUID.fromString(claims.getStringClaim("ws")), claims.getExpirationTime().toInstant());
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException e) {
            throw invalid("The token could not be read.");
        }
    }

    private static ApiProblem invalid(String message) {
        return new ApiProblem(401, "invalid_token", message);
    }
}
