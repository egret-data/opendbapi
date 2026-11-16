package io.opendbapi.serving;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Request routing is covered end to end by the app's EndpointIntegrationTest. */
class ServingDispatcherTest {

    @Test
    void endpointPathIsWhatFollowsContextAndWorkspace() {
        // Act + Assert
        assertThat(ServingDispatcher.endpointPath("/api/sales/v1/users/42", "", "sales")).isEqualTo("/v1/users/42");
        assertThat(ServingDispatcher.endpointPath("/app/data/sales/x", "/app", "sales")).isEqualTo("/x");
    }
}
