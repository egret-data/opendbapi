package io.opendbapi.core.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.opendbapi.core.secret.SecretResolver;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RS256 keys for access tokens. Every instance signs with the newest active key stored in the
 * metadata database; the private key is sealed with the master key. Without a master key a
 * key is generated per process and tokens do not survive a restart (development only).
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final JdbcClient jdbc;
    private final SecretResolver secrets;
    private final TransactionTemplate transactions;
    private final Map<String, RSAKey> publicKeys = new ConcurrentHashMap<>();
    private volatile RSAKey current;

    public SigningKeys(JdbcClient jdbc, SecretResolver secrets, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.transactions = transactions;
    }

    /** The key new tokens are signed with. */
    public RSAKey current() {
        RSAKey key = current;
        if (key == null) {
            synchronized (this) {
                if (current == null) {
                    current = loadOrCreate();
                    publicKeys.put(current.getKeyID(), current.toPublicJWK());
                }
                key = current;
            }
        }
        return key;
    }

    /** Public key by id, for verification; reloads from the database for a key minted by another instance. */
    public RSAKey publicKey(String kid) {
        current();
        RSAKey key = publicKeys.get(kid);
        if (key == null && kid != null) {
            key = jdbc.sql("select public_key from signing_key where kid = ? and retired_at is null").param(kid)
                    .query(String.class).optional().map(SigningKeys::parse).orElse(null);
            if (key != null) {
                publicKeys.put(kid, key);
            }
        }
        return key;
    }

    public JWKSet publicSet() {
        current();
        List<RSAKey> active = jdbc.sql("select public_key from signing_key where retired_at is null order by created_at desc")
                .query(String.class).list().stream().map(SigningKeys::parse).toList();
        List<com.nimbusds.jose.jwk.JWK> keys = active.isEmpty() ? List.of(current.toPublicJWK())
                : active.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k).toList();
        return new JWKSet(keys);
    }

    private RSAKey loadOrCreate() {
        if (!secrets.canSeal()) {
            log.warn("No master key: access tokens are signed with a key that lives only in this process.");
            return generate();
        }
        return transactions.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtext('opendbapi.signing_key'))").query().singleRow();
            var stored = jdbc.sql("select private_key from signing_key where retired_at is null order by created_at desc limit 1")
                    .query(String.class).optional();
            if (stored.isPresent()) {
                return parse(secrets.resolve(stored.get()));
            }
            RSAKey key = generate();
            jdbc.sql("insert into signing_key (kid, private_key, public_key) values (?, ?, ?)")
                    .params(key.getKeyID(), secrets.seal(key.toJSONString()), key.toPublicJWK().toJSONString()).update();
            log.info("Created access-token signing key {}", key.getKeyID());
            return key;
        });
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate signing key", e);
        }
    }

    private static RSAKey parse(String json) {
        try {
            return RSAKey.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("Stored signing key is not a valid JWK", e);
        }
    }
}
