package io.opendbapi.serving;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.endpoint.QueryExecutor;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Row writers for streamed responses (spec §9.6). NDJSON: one JSON object per line; when the
 * stream is cut short a last line {@code {"_meta":{...}}} says why. CSV (RFC 4180): a header
 * row, then one line per row; nested values are written as JSON text.
 */
final class StreamWriters {

    private StreamWriters() {
    }

    static QueryExecutor.RowSink ndjson(OutputStream out, ObjectMapper json, String requestId) {
        return new QueryExecutor.RowSink() {
            private final Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
            private JsonGenerator g;
            private String[] names;
            private int sinceFlush;

            @Override
            public void start(List<QueryExecutor.ResultColumn> columns) throws IOException {
                names = columns.stream().map(QueryExecutor.ResultColumn::name).toArray(String[]::new);
                // One generator for the whole stream; flushing it must not flush the socket per row.
                g = json.getFactory().createGenerator(writer);
                g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                g.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
                g.setRootValueSeparator(null);
            }

            @Override
            public void row(Object[] values) throws IOException {
                g.writeStartObject();
                for (int i = 0; i < names.length; i++) {
                    g.writeFieldName(names[i]);
                    json.writeValue(g, values[i]);
                }
                g.writeEndObject();
                g.writeRaw('\n');
                if (++sinceFlush >= 1000) {
                    g.flush();
                    writer.flush();
                    sinceFlush = 0;
                }
            }

            @Override
            public void end(boolean truncated) throws IOException {
                if (truncated) {
                    meta(Map.of("truncated", true, "requestId", requestId));
                }
                g.flush();
                writer.flush();
            }

            @Override
            public void failed(String code) throws IOException {
                meta(Map.of("error", code, "requestId", requestId));
                g.flush();
                writer.flush();
            }

            private void meta(Map<String, Object> meta) throws IOException {
                g.writeObject(Map.of("_meta", meta));
                g.writeRaw('\n');
            }
        };
    }

    static QueryExecutor.RowSink csv(OutputStream out, ObjectMapper json) {
        return new QueryExecutor.RowSink() {
            private final Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
            private int sinceFlush;

            @Override
            public void start(List<QueryExecutor.ResultColumn> columns) throws IOException {
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    writer.write(quote(columns.get(i).name()));
                }
                writer.write("\r\n");
            }

            @Override
            public void row(Object[] values) throws IOException {
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    Object v = values[i];
                    if (v != null) {
                        writer.write(quote(v instanceof List<?> || v instanceof Map<?, ?> ? json.writeValueAsString(v) : String.valueOf(v)));
                    }
                }
                writer.write("\r\n");
                if (++sinceFlush >= 500) {
                    writer.flush();
                    sinceFlush = 0;
                }
            }

            @Override
            public void end(boolean truncated) throws IOException {
                writer.flush();
            }

            @Override
            public void failed(String code) throws IOException {
                writer.flush(); // CSV has no place for an error; the caller sees a short file and the log has the cause
            }
        };
    }

    static String quote(String s) {
        boolean needs = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return needs ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
