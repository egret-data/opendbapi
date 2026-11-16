package io.opendbapi.core.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.error.ApiProblem;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecretResolverTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static SecretResolver resolver(String masterKey, MockEnvironment env) {
        var auth = new OpenDbApiProperties.Auth(true, 5, Duration.ofMinutes(15), "", "/");
        var props = new OpenDbApiProperties(OpenDbApiProperties.Mode.STANDALONE, masterKey,
                new OpenDbApiProperties.Serving("api", ""), auth, new OpenDbApiProperties.Datasources(null, null));
        return new SecretResolver(env, props);
    }

    @Test
    void sealedSecretRoundTripsAndIsNotPlainText() {
        // Arrange
        SecretResolver secrets = resolver(KEY, new MockEnvironment());

        // Act
        String ref = secrets.seal("bind-password");

        // Assert
        assertThat(ref).startsWith("enc:v1:").doesNotContain("bind-password");
        assertThat(secrets.resolve(ref)).isEqualTo("bind-password");
        assertThat(secrets.seal("bind-password")).as("random IV").isNotEqualTo(ref);
    }

    @Test
    void envReferenceReadsTheEnvironmentAtUseTime() {
        // Arrange
        MockEnvironment env = new MockEnvironment().withProperty("LDAP_BIND_PASSWORD", "from-env");

        // Act + Assert
        assertThat(resolver("", env).resolve("env:LDAP_BIND_PASSWORD")).isEqualTo("from-env");
    }

    @Test
    void missingEnvVariableIsAClearError() {
        // Act + Assert
        assertThatThrownBy(() -> resolver("", new MockEnvironment()).resolve("env:NOPE"))
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining("env:NOPE is not set");
    }

    @Test
    void sealingWithoutMasterKeyIsRefused() {
        // Act + Assert
        assertThatThrownBy(() -> resolver("", new MockEnvironment()).seal("x"))
                .isInstanceOf(ApiProblem.class)
                .extracting("code").isEqualTo("master_key_missing");
    }

    @Test
    void secretSealedWithAnotherKeyCannotBeRead() {
        // Arrange
        String ref = resolver(OTHER_KEY, new MockEnvironment()).seal("x");

        // Act + Assert
        assertThatThrownBy(() -> resolver(KEY, new MockEnvironment()).resolve(ref))
                .isInstanceOf(ApiProblem.class)
                .extracting("code").isEqualTo("secret_unreadable");
    }

    @Test
    void referencesPassThroughAndPlainTextGetsSealed() {
        // Arrange
        SecretResolver secrets = resolver(KEY, new MockEnvironment());

        // Act + Assert
        assertThat(secrets.toReference("env:X")).isEqualTo("env:X");
        assertThat(secrets.toReference("plain")).startsWith("enc:v1:");
        assertThat(secrets.toReference("")).isNull();
    }
}
