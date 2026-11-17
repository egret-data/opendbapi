package io.opendbapi.sql.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.sql.policy.Masks.Masker;
import io.opendbapi.sql.policy.Masks.MaskStyle;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MasksTest {

    private static final byte[] SALT = "salt-salt-salt-salt".getBytes(StandardCharsets.UTF_8);

    private static Object mask(MaskStyle type, Object value) {
        return new Masker(type, 2, 1, SALT).apply(value);
    }

    @Test
    void masksPhonesEmailsAndPartials() {
        // Assert
        assertThat(mask(MaskStyle.phone, "0901234123")).isEqualTo("090****123");
        assertThat(mask(MaskStyle.email, "nam.nguyen@example.com")).isEqualTo("n***@example.com");
        assertThat(mask(MaskStyle.email, "not-an-email")).isEqualTo("***");
        assertThat(mask(MaskStyle.partial, "ABCDEF")).isEqualTo("AB***F");
        assertThat(mask(MaskStyle.partial, 123456)).isEqualTo("12***6");
    }

    @Test
    void shortValuesAreFullyHidden() {
        // Assert
        assertThat(mask(MaskStyle.phone, "12345")).isEqualTo("*****");
        assertThat(mask(MaskStyle.partial, "abc")).isEqualTo("***");
    }

    @Test
    void hashIsStableAndSalted() {
        // Act
        Object a = mask(MaskStyle.hash, "secret");
        Object b = mask(MaskStyle.hash, "secret");
        Object other = new Masker(MaskStyle.hash, 0, 0, "another-salt-value".getBytes(StandardCharsets.UTF_8)).apply("secret");

        // Assert
        assertThat(a).isEqualTo(b).asString().hasSize(64);
        assertThat(other).isNotEqualTo(a);
    }

    @Test
    void nullStaysNullAndNullifyHidesEverything() {
        // Assert
        assertThat(mask(MaskStyle.email, null)).isNull();
        assertThat(mask(MaskStyle.nullify, "anything")).isNull();
    }
}
