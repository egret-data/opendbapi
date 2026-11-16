package io.opendbapi.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.opendbapi.core.error.ApiProblem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccessTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private final UUID sa = UUID.randomUUID();
    private final UUID ws = UUID.randomUUID();
    private SigningKeys keys;
    private RSAKey key;

    @BeforeEach
    void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        keys = mock(SigningKeys.class);
        when(keys.current()).thenReturn(key);
        when(keys.publicKey("k1")).thenReturn(key.toPublicJWK());
    }

    private AccessTokens at(Instant now) {
        return new AccessTokens(keys, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenVerifiesWithItsClaims() {
        // Arrange
        String token = at(NOW).issue(sa, "odc_x", ws).token();

        // Act
        AccessTokens.Caller caller = at(NOW.plusSeconds(60)).verify(token);

        // Assert
        assertThat(caller.serviceAccountId()).isEqualTo(sa);
        assertThat(caller.clientId()).isEqualTo("odc_x");
        assertThat(caller.workspaceId()).isEqualTo(ws);
    }

    @Test
    void expiredTokenIsRefused() {
        // Arrange
        String token = at(NOW).issue(sa, "odc_x", ws).token();

        // Act + Assert
        assertThatThrownBy(() -> at(NOW.plus(Duration.ofMinutes(16))).verify(token))
                .isInstanceOfSatisfying(ApiProblem.class, p -> assertThat(p.code()).isEqualTo("invalid_token"))
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithAnotherAlgorithmOrKeyIsRefused() throws Exception {
        // Arrange: HS256 with the public modulus as secret (algorithm confusion attempt)
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("opendbapi").subject(sa.toString()).claim("cid", "odc_x")
                .claim("ws", ws.toString()).expirationTime(java.util.Date.from(NOW.plusSeconds(600))).build();
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1")
                .type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(), claims);
        hs.sign(new MACSigner(key.getModulus().decode()));
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(), claims);
        forged.sign(new com.nimbusds.jose.crypto.RSASSASigner(other));

        // Act + Assert
        assertThatThrownBy(() -> at(NOW).verify(hs.serialize())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> at(NOW).verify(forged.serialize())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> at(NOW).verify("not-a-jwt")).isInstanceOf(ApiProblem.class);
    }

    @Test
    void clientSecretsHashAndCompare() {
        // Arrange
        String secret = ClientSecrets.newSecret();

        // Act
        String stored = ClientSecrets.hash(secret);

        // Assert
        assertThat(ClientSecrets.matches(secret, stored)).isTrue();
        assertThat(ClientSecrets.matches(secret + "x", stored)).isFalse();
        assertThat(ClientSecrets.newClientId()).startsWith("odc_").isNotEqualTo(ClientSecrets.newClientId());
    }
}
