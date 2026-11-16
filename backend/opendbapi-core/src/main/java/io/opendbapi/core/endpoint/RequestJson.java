package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * How serving reads request bodies: decimals stay exact ({@code 0.1}, {@code 12.50}) instead
 * of becoming doubles, and nesting/length are bounded.
 */
public final class RequestJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    static {
        MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxStringLength(1_000_000)
                .maxNumberLength(100)
                .build());
    }

    private RequestJson() {
    }
}
