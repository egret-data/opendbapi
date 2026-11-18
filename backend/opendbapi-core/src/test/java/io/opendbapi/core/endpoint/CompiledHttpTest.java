package io.opendbapi.core.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.sql.template.TemplateException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompiledHttpTest {

    private static CompiledHttp compile(String method, String path, List<EndpointDefinition.HttpField> query,
                                        List<EndpointDefinition.HttpField> body, String resultPath) {
        return CompiledHttp.compile(new EndpointDefinition.HttpCall(method, path, query, body, resultPath));
    }

    @Test
    void rendersPathQueryAndBodyFromParameters() {
        // Arrange
        CompiledHttp http = compile("POST", "/customers/{id}/notes", List.of(new EndpointDefinition.HttpField("tag", "tags")),
                List.of(new EndpointDefinition.HttpField("text", "note"), new EndpointDefinition.HttpField("by", "author.name")), "");
        Map<String, Object> params = new HashMap<>(Map.of("id", 7L, "tags", List.of("a b", "c&d"), "note", "hi",
                "author", Map.of("name", "ana")));

        // Act
        CompiledHttp.OutgoingRequest r = http.render(params);

        // Assert
        assertThat(r.pathAndQuery()).isEqualTo("/customers/7/notes?tag=a%20b&tag=c%26d");
        assertThat(r.body()).containsExactly(Map.entry("text", "hi"), Map.entry("by", "ana"));
        assertThat(r.described()).isEqualTo("POST /customers/{id}/notes");
        assertThat(http.parameterNames()).containsExactlyInAnyOrder("id", "tags", "note", "author");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "."})
    void dotSegmentsAreEncoded(String value) {
        // Act
        String path = compile("GET", "/files/{name}", List.of(), List.of(), "").render(Map.of("name", value)).pathAndQuery();

        // Assert
        assertThat(path).isEqualTo("/files/" + value.replace(".", "%2E"));
    }

    @Test
    void nullQueryValuesAreLeftOutAndAMissingPathValueIsRefused() {
        // Arrange
        CompiledHttp http = compile("GET", "/orders/{id}", List.of(new EndpointDefinition.HttpField("status", "status")), List.of(), "");
        Map<String, Object> noStatus = new HashMap<>();
        noStatus.put("id", 1);
        noStatus.put("status", null);

        // Act + Assert
        assertThat(http.render(noStatus).pathAndQuery()).isEqualTo("/orders/1");
        assertThatThrownBy(() -> http.render(new HashMap<>())).isInstanceOf(ApiProblem.class).hasMessageContaining("{id}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "/orders?x=1", "/orders#top", "/a/../b", "/a b"})
    void pathsMustBePlainAbsolutePaths(String path) {
        // Act + Assert
        assertThatThrownBy(() -> compile("GET", path, List.of(), List.of(), "")).isInstanceOf(TemplateException.class);
    }

    @Test
    void getRequestsHaveNoBodyAndMethodsAreLimited() {
        // Act + Assert
        assertThatThrownBy(() -> compile("GET", "/x", List.of(), List.of(new EndpointDefinition.HttpField("a", "b")), ""))
                .isInstanceOf(TemplateException.class);
        assertThatThrownBy(() -> compile("TRACE", "/x", List.of(), List.of(), "")).isInstanceOf(TemplateException.class);
        assertThat(compile("DELETE", "/x", List.of(), List.of(), "").writes()).isTrue();
        assertThat(compile("GET", "/x", List.of(), List.of(), "").writes()).isFalse();
    }

    @Test
    void rowsComeFromTheResultPath() throws Exception {
        // Arrange
        var json = RequestJson.MAPPER.readTree("{\"data\": {\"items\": [{\"id\": 1}, 2], \"one\": {\"id\": 3}}}");

        // Act + Assert
        assertThat(compile("GET", "/x", List.of(), List.of(), "/data/items").rows(json))
                .containsExactly(Map.of("id", 1), Map.of("value", 2));
        assertThat(compile("GET", "/x", List.of(), List.of(), "/data/one").rows(json)).containsExactly(Map.of("id", 3));
        assertThatThrownBy(() -> compile("GET", "/x", List.of(), List.of(), "/nope").rows(json))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("/nope");
    }
}
