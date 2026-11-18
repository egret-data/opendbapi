package io.opendbapi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpConfigTest {

    private static HttpConfig config(String baseUrl, String auth, Map<String, String> headers) {
        return new HttpConfig(baseUrl, auth, "svc", "X-API-Key", headers, null);
    }

    @Test
    void acceptsAPlainBaseUrlAndDropsTheTrailingSlash() {
        // Act
        HttpConfig cfg = config("https://api.example.com/v1/", "bearer", Map.of("X-Tenant", "acme")).validate();

        // Assert
        assertThat(cfg.baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(cfg.host()).isEqualTo("api.example.com");
        assertThat(cfg.connectTimeoutMs()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://h/x", "file:///etc/passwd", "https://u:p@h/x", "https://h/x?k=1", "https://h/x#f", "not a url", "/v1"})
    void refusesUrlsThatAreNotPlainHttp(String url) {
        // Act + Assert
        assertThatThrownBy(() -> config(url, "none", Map.of()).validate()).isInstanceOf(ApiProblem.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "Cookie", "Host", "Proxy-Authorization", "Transfer-Encoding", "bad header"})
    void staticHeadersCannotCarryCredentialsOrRouting(String header) {
        // Act + Assert
        assertThatThrownBy(() -> config("https://h", "none", Map.of(header, "x")).validate()).isInstanceOf(ApiProblem.class);
    }

    @Test
    void headerValuesCannotSplitTheRequest() {
        // Act + Assert
        assertThatThrownBy(() -> config("https://h", "none", Map.of("X-A", "a\r\nX-B: b")).validate()).isInstanceOf(ApiProblem.class);
    }

    @Test
    void authenticationNeedsWhatItSends() {
        // Act + Assert
        assertThatThrownBy(() -> new HttpConfig("https://h", "basic", null, null, null, null).validate()).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> new HttpConfig("https://h", "header", null, null, null, null).validate()).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> new HttpConfig("https://h", "oauth", null, null, null, null).validate()).isInstanceOf(ApiProblem.class);
        assertThat(new HttpConfig("https://h", "header", null, "X-API-Key", null, null).validate().auth()).isEqualTo("header");
    }
}
