package io.opendbapi.core.datasource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostPolicyTest {

    @Test
    void cloudMetadataEndpointsAreBlockedByDefault() {
        // Arrange
        HostPolicy policy = new HostPolicy(HostPolicy.DEFAULT_DENIED, List.of());

        // Act + Assert
        assertThatThrownBy(() -> policy.check("169.254.169.254")).isInstanceOf(ApiProblem.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> policy.check("metadata.google.internal")).isInstanceOf(ApiProblem.class);
        assertThatCode(() -> policy.check("127.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void namesAreResolvedSoAnAliasCannotDodgeTheBlock() {
        // Arrange: "localhost" resolves to 127.0.0.1
        HostPolicy policy = new HostPolicy(List.of("127.0.0.0/8"), List.of());

        // Act + Assert
        assertThatThrownBy(() -> policy.check("localhost")).isInstanceOf(ApiProblem.class);
    }

    @Test
    void anAllowListRestrictsToItsEntries() {
        // Arrange
        HostPolicy policy = new HostPolicy(List.of(), List.of("10.20.0.0/16", "*.db.corp.local"));

        // Act + Assert
        assertThatCode(() -> policy.check("10.20.3.4")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("pg1.db.corp.local")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("10.30.0.1")).isInstanceOf(ApiProblem.class)
                .hasMessageContaining("allowed");
    }
}
