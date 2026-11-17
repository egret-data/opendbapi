package io.opendbapi.core.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.endpoint.EndpointDefinition.Nest;
import io.opendbapi.core.endpoint.EndpointDefinition.NestCollection;
import io.opendbapi.core.endpoint.EndpointDefinition.Output;
import io.opendbapi.core.error.ApiProblem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultMapperTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void nestsChildrenNamesInCamelCaseAndConvertsTypes() {
        // Arrange
        Output out = new Output("camelCase", "list", Map.of("order_total", "decimal_string", "created_at", "epoch_ms"),
                List.of(new Nest("id", List.of(new NestCollection("orders", "order_", "order_id")))));
        List<Map<String, Object>> rows = List.of(
                row("id", 1L, "created_at", "2026-01-01T00:00:00Z", "order_id", 10L, "order_total", new BigDecimal("1.50")),
                row("id", 1L, "created_at", "2026-01-01T00:00:00Z", "order_id", 11L, "order_total", new BigDecimal("2.00")),
                row("id", 2L, "created_at", "2026-01-02", "order_id", null, "order_total", null));

        // Act
        Object data = ResultMapper.map(out, rows);

        // Assert
        assertThat(data).isEqualTo(List.of(
                row("id", 1L, "createdAt", 1767225600000L, "orders", List.of(row("id", 10L, "total", "1.50"), row("id", 11L, "total", "2.00"))),
                row("id", 2L, "createdAt", 1767312000000L, "orders", List.of())));
    }

    @Test
    void singleAndScalarShapes() {
        // Arrange
        List<Map<String, Object>> rows = List.of(row("n", 7L, "flag", 1), row("n", 8L, "flag", 0));

        // Act + Assert
        assertThat(ResultMapper.map(new Output(null, "single", Map.of("flag", "boolean"), null), rows)).isEqualTo(row("n", 7L, "flag", true));
        assertThat(ResultMapper.map(new Output(null, "scalar", null, null), rows)).isEqualTo(7L);
        assertThatThrownBy(() -> ResultMapper.map(new Output(null, "single", null, null), List.of()))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("No matching record");
    }

    @Test
    void camelCaseLeavesNamesWithoutUnderscoresAlone() {
        // Assert
        assertThat(ResultMapper.camelCase("created_at")).isEqualTo("createdAt");
        assertThat(ResultMapper.camelCase("CUSTOMER_ID")).isEqualTo("customerId");
        assertThat(ResultMapper.camelCase("_private")).isEqualTo("private");
        assertThat(ResultMapper.camelCase("totalAmount")).isEqualTo("totalAmount");
    }

    @Test
    void cursorsRoundTripWithTheirTypes() {
        // Arrange
        var keys = List.of(new EndpointDefinition.KeysetKey("created_at", true), new EndpointDefinition.KeysetKey("id", false));
        var columns = List.of(new QueryExecutor.ResultColumn("created_at", "timestamp"), new QueryExecutor.ResultColumn("id", "int8"));

        // Act
        String cursor = KeysetCursor.after(keys, columns, row("created_at", "2026-01-01T10:00", "id", 42L));
        List<Object> values = KeysetCursor.decode(cursor, 2);

        // Assert
        assertThat(values).containsExactly(java.time.LocalDateTime.parse("2026-01-01T10:00"), 42L);
        assertThatThrownBy(() -> KeysetCursor.decode("not-a-cursor", 2)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> KeysetCursor.decode(cursor, 3)).isInstanceOf(ApiProblem.class);
    }
}
