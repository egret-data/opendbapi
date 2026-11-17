package io.opendbapi.core.endpoint.test;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A saved check of an endpoint (spec §11.3): parameter values, optionally a service account to
 * call as, and what the answer must look like. It runs against the saved draft and always
 * rolls back, like preview.
 */
public record EndpointTestCase(
        UUID id,
        String name,
        @Schema(nullable = true, description = "Call as this service account (grants, rules and data policies apply); "
                + "null runs like preview") UUID serviceAccountId,
        @Schema(description = "Parameter values by name; page and size select a page") Map<String, Object> params,
        TestExpectation expect,
        @Schema(nullable = true) TestCaseResult lastResult,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Every given field must hold. {@code status} defaults to 200.
     *
     * @param schemaMatchesLive the result column names equal the live version's output schema
     */
    public record TestExpectation(
            @Schema(nullable = true) Integer status,
            @Schema(nullable = true) RowCountRange rowCount,
            @Schema(nullable = true) List<JsonPathCheck> jsonPath,
            @Schema(nullable = true) Boolean schemaMatchesLive,
            @Schema(nullable = true) Integer maxDurationMs) {

        public TestExpectation {
            jsonPath = jsonPath == null ? List.of() : List.copyOf(jsonPath);
        }
    }

    public record RowCountRange(@Schema(nullable = true) Integer min, @Schema(nullable = true) Integer max) {
    }

    /**
     * A JSONPath into the response {@code {data, meta}}, e.g. {@code $.data[0].status}.
     *
     * @param equals the expected JSON value; null skips the value check; use {@code exists: false} to require a missing or null value
     */
    public record JsonPathCheck(String path, @Schema(nullable = true) JsonNode equals, @Schema(nullable = true) Boolean exists) {
    }

    /** One run of a test case. */
    public record TestCaseResult(UUID testId, String name, boolean passed, int status, @Schema(nullable = true) String code,
                                 long durationMs, @Schema(nullable = true) Integer rowCount, List<String> failures, Instant ranAt) {
    }
}
