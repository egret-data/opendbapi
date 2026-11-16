package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Streamed NDJSON and CSV responses (spec §9.6). */
class StreamingIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;
    private String dsId;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "st-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Stream " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        dsId = admin.post("/admin/api/ws/" + ws + "/datasources", ds).expect(201).json().path("id").asText();
    }

    private void endpoint(String path, String sql, Map<String, Object> streaming, Integer streamMaxRows) {
        String base = "/admin/api/ws/" + ws + "/endpoints";
        String id = admin.post(base, Map.of("name", path, "method", "GET", "path", path, "access", "public", "datasourceId", dsId))
                .expect(201).json().path("summary").path("id").asText();
        Map<String, Object> limits = new HashMap<>(Map.of("timeoutSeconds", 30, "maxRows", 100));
        limits.put("streamMaxRows", streamMaxRows);
        Map<String, Object> def = new HashMap<>(Map.of("params", List.of(), "limits", limits, "task", Map.of("type", "sql",
                "datasourceId", dsId, "transaction", false, "statements", List.of(Map.of("id", "main", "template", sql)))));
        if (streaming != null) {
            def.put("streaming", streaming);
        }
        admin.patch(base + "/" + id, Map.of("name", path, "method", "GET", "path", path, "access", "public", "definition", def))
                .expect(200);
        admin.post(base + "/" + id + ":publish", Map.of()).expect(200);
    }

    private HttpResponse<String> get(String pathAndQuery, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/" + ws + "/" + pathAndQuery));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ndjsonStreamsBeyondMaxRowsAndAcrossFetchBatches() throws Exception {
        // Arrange: maxRows is 100 for JSON, the stream may return 5000
        endpoint("series", "select g as n, 'row ' || g as label from generate_series(1, 2500) g", Map.of("allowed", true), 5000);

        // Act
        HttpResponse<String> streamed = get("series?format=ndjson", null);
        HttpResponse<String> json = get("series", null);

        // Assert
        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(streamed.headers().firstValue("Content-Type").orElse("")).startsWith("application/x-ndjson");
        String[] lines = streamed.body().split("\n");
        assertThat(lines).hasSize(2500);
        assertThat(lines[2499]).isEqualTo("{\"n\":2500,\"label\":\"row 2500\"}");
        assertThat(json.body()).contains("\"truncated\":true");
    }

    @Test
    void csvByAcceptHeaderQuotesWhatNeedsQuoting() throws Exception {
        // Arrange
        endpoint("csv", "select 1 as id, 'a,b' as name, 'say \"hi\"' as quote, null::text as empty",
                Map.of("allowed", true, "formats", List.of("csv")), null);

        // Act
        HttpResponse<String> csv = get("csv", "text/csv");

        // Assert
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body()).isEqualTo("id,name,quote,empty\r\n1,\"a,b\",\"say \"\"hi\"\"\",\r\n");
        assertThat(get("csv?format=ndjson", null).statusCode()).isEqualTo(406);
    }

    @Test
    void streamingMustBeAllowed() throws Exception {
        // Arrange
        endpoint("plain", "select 1 as n", null, null);

        // Act + Assert
        assertThat(get("plain?format=csv", null).statusCode()).isEqualTo(406);
        assertThat(get("plain?format=xml", null).statusCode()).isEqualTo(400);
        assertThat(get("plain", "application/x-ndjson").statusCode()).isEqualTo(406);
    }

    @Test
    void streamStopsAtTheLimitAndSaysSo() throws Exception {
        // Arrange
        endpoint("capped", "select g as n from generate_series(1, 50) g", Map.of("allowed", true), 10);

        // Act
        String[] lines = get("capped?format=ndjson", null).body().split("\n");

        // Assert
        assertThat(lines).hasSize(11);
        assertThat(lines[10]).startsWith("{\"_meta\":").contains("\"truncated\":true");
    }

    @Test
    void errorBeforeTheFirstRowIsAProblemAndLaterOneIsReportedInBand() throws Exception {
        // Arrange: fails on the first row; fails on row 1500, after the first fetch batch was sent
        endpoint("early", "select 1 / (g - 1) as n from generate_series(1, 10) g", Map.of("allowed", true), null);
        endpoint("late", "select 1 / (g - 1500) as n from generate_series(1, 3000) g", Map.of("allowed", true), 5000);

        // Act
        HttpResponse<String> early = get("early?format=ndjson", null);
        HttpResponse<String> late = get("late?format=ndjson", null);

        // Assert
        assertThat(early.statusCode()).isEqualTo(500);
        assertThat(early.body()).contains("query_failed").doesNotContain("division");
        assertThat(late.statusCode()).isEqualTo(200);
        String[] lines = late.body().split("\n");
        assertThat(lines[lines.length - 1]).contains("\"error\":\"query_failed\"");
        assertThat(late.body()).doesNotContain("division");
    }
}
