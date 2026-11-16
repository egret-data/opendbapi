package io.opendbapi.core.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.endpoint.EndpointDefinition.Param;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamIn;
import io.opendbapi.core.endpoint.EndpointDefinition.ParamType;
import io.opendbapi.core.error.ApiProblem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParamBinderTest {

    private static final ObjectMapper JSON = RequestJson.MAPPER;

    private static Param p(String name, ParamIn in, ParamType type) {
        return new Param(name, in, type, false, null, null, null, null, null, null, null);
    }

    private static ParamBinder.Request query(Map<String, List<String>> query) {
        return new ParamBinder.Request(Map.of(), query, Map.of(), null);
    }

    @Test
    void coercesEveryScalarType() {
        // Arrange
        List<Param> params = List.of(p("s", ParamIn.query, ParamType.string), p("n", ParamIn.query, ParamType.bigint),
                p("d", ParamIn.query, ParamType.double_), p("m", ParamIn.query, ParamType.decimal),
                p("b", ParamIn.query, ParamType.boolean_), p("day", ParamIn.query, ParamType.date),
                p("at", ParamIn.query, ParamType.datetime));

        // Act
        Map<String, Object> bound = ParamBinder.bind(params, query(Map.of("s", List.of("x"), "n", List.of("42"),
                "d", List.of("1.5"), "m", List.of("10.25"), "b", List.of("true"), "day", List.of("2026-11-16"),
                "at", List.of("2026-11-16 08:30:00"))), Set.of());

        // Assert
        assertThat(bound).containsEntry("s", "x").containsEntry("n", 42L).containsEntry("d", 1.5)
                .containsEntry("m", new BigDecimal("10.25")).containsEntry("b", true)
                .containsEntry("day", LocalDate.of(2026, 11, 16))
                .containsEntry("at", LocalDateTime.of(2026, 11, 16, 8, 30));
    }

    @Test
    void datesAcceptIsoAndEpochMillis() {
        // Arrange
        List<Param> params = List.of(p("at", ParamIn.query, ParamType.datetime));

        // Act + Assert
        assertThat(ParamBinder.bind(params, query(Map.of("at", List.of("2026-11-16T08:30:00"))), Set.of()).get("at"))
                .isEqualTo(LocalDateTime.of(2026, 11, 16, 8, 30));
        assertThat(ParamBinder.bind(params, query(Map.of("at", List.of("2026-11-16"))), Set.of()).get("at"))
                .isEqualTo(LocalDateTime.of(2026, 11, 16, 0, 0));
        assertThat(ParamBinder.bind(params, query(Map.of("at", List.of("0"))), Set.of()).get("at"))
                .isInstanceOf(LocalDateTime.class);
    }

    @Test
    void arraysFromRepeatedOrBracketedOrCommaSeparatedQueryValues() {
        // Arrange
        List<Param> params = List.of(p("ids", ParamIn.query, ParamType.array_bigint));

        // Act + Assert
        assertThat(ParamBinder.bind(params, query(Map.of("ids", List.of("1", "2"))), Set.of()).get("ids")).isEqualTo(List.of(1L, 2L));
        assertThat(ParamBinder.bind(params, query(Map.of("ids[]", List.of("3"))), Set.of()).get("ids")).isEqualTo(List.of(3L));
        assertThat(ParamBinder.bind(params, query(Map.of("ids", List.of("4,5"))), Set.of()).get("ids")).isEqualTo(List.of(4L, 5L));
    }

    @Test
    void readsPathHeaderAndJsonBody() throws Exception {
        // Arrange
        List<Param> params = List.of(p("id", ParamIn.path, ParamType.bigint), p("X-Tenant", ParamIn.header, ParamType.string),
                p("tags", ParamIn.body, ParamType.array_string), p("amount", ParamIn.body, ParamType.decimal));
        JsonNode body = JSON.readTree("{\"tags\":[\"a\",\"b\"],\"amount\":12.50}");

        // Act
        Map<String, Object> bound = ParamBinder.bind(params,
                new ParamBinder.Request(Map.of("id", "7"), Map.of(), Map.of("x-tenant", "acme"), body), Set.of());

        // Assert
        assertThat(bound).containsEntry("id", 7L).containsEntry("X-Tenant", "acme")
                .containsEntry("tags", List.of("a", "b")).containsEntry("amount", new BigDecimal("12.50"));
    }

    @Test
    void requiredMissingAndBadValuesAre400sNamingTheParameter() {
        // Arrange
        Param required = new Param("id", ParamIn.query, ParamType.bigint, true, null, null, null, null, null, null, null);

        // Act + Assert
        assertThatThrownBy(() -> ParamBinder.bind(List.of(required), query(Map.of()), Set.of()))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("id is required")
                .extracting("code").isEqualTo("invalid_param");
        assertThatThrownBy(() -> ParamBinder.bind(List.of(required), query(Map.of("id", List.of("abc"))), Set.of()))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("id must be a whole number");
    }

    @Test
    void defaultsApplyWhenAbsent() {
        // Arrange
        Param withDefault = new Param("status", ParamIn.query, ParamType.string, false, JSON.getNodeFactory().textNode("active"),
                null, null, null, null, null, null);

        // Act + Assert
        assertThat(ParamBinder.bind(List.of(withDefault), query(Map.of()), Set.of())).containsEntry("status", "active");
    }

    @Test
    void constraintsAreEnforced() {
        // Arrange
        Param status = new Param("status", ParamIn.query, ParamType.string, false, null, List.of("new", "paid"), null, null, null, null, null);
        Param age = new Param("age", ParamIn.query, ParamType.bigint, false, null, null, BigDecimal.ONE, BigDecimal.valueOf(120), null, null, null);
        Param code = new Param("code", ParamIn.query, ParamType.string, false, null, null, null, null, "^[A-Z]{3}$", null, null);
        Param ids = new Param("ids", ParamIn.query, ParamType.array_bigint, false, null, null, null, null, null, 2, null);

        // Act + Assert
        assertThatThrownBy(() -> ParamBinder.bind(List.of(status), query(Map.of("status", List.of("lost"))), Set.of()))
                .hasMessageContaining("status must be one of: new, paid");
        assertThatThrownBy(() -> ParamBinder.bind(List.of(age), query(Map.of("age", List.of("200"))), Set.of()))
                .hasMessageContaining("age must be at most 120");
        assertThatThrownBy(() -> ParamBinder.bind(List.of(code), query(Map.of("code", List.of("abc"))), Set.of()))
                .hasMessageContaining("code does not match");
        assertThatThrownBy(() -> ParamBinder.bind(List.of(ids), query(Map.of("ids", List.of("1", "2", "3"))), Set.of()))
                .hasMessageContaining("ids accepts at most 2 values");
    }

    @Test
    void undeclaredQueryParametersAreRefusedButReservedOnesPass() {
        // Arrange
        List<Param> params = List.of(p("q", ParamIn.query, ParamType.string));

        // Act + Assert
        assertThatThrownBy(() -> ParamBinder.bind(params, query(Map.of("qq", List.of("x"))), Set.of()))
                .isInstanceOf(ApiProblem.class).hasMessageContaining("Unknown parameter qq");
        assertThat(ParamBinder.bind(params, query(Map.of("page", List.of("2"))), Set.of("page", "size"))).doesNotContainKey("page");
    }

    @Test
    void absentOptionalParametersBindAsNull() {
        // Act
        Map<String, Object> bound = ParamBinder.bind(List.of(p("q", ParamIn.query, ParamType.string)), query(Map.of()), Set.of());

        // Assert
        assertThat(bound).containsKey("q");
        assertThat(bound.get("q")).isNull();
    }
}
