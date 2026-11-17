package io.opendbapi.core.endpoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns a query result into the response's {@code data} and page {@code meta} (spec §9.1
 * steps 13–14, §9.4), the same way for real calls and test cases.
 */
public final class ResponseBody {

    public record Parts(Object data, Map<String, Object> pageMeta, long rows) {
    }

    private ResponseBody() {
    }

    /**
     * @param masking applied to the rows after the next cursor is taken (masks must not change
     *                where the next page starts) and before mapping
     */
    public static Parts build(EndpointDefinition def, QueryExecutor.QueryResult result, QueryExecutor.PageRequest page,
                              Consumer<QueryExecutor.QueryResult> masking) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (result.value() instanceof QueryExecutor.ScriptValue v) {
            // Spec §7.4 full mode: the script's own answer, its queries already masked.
            if (result.truncated()) {
                meta.put("truncated", true);
            }
            return new Parts(v.data(), meta, v.rows());
        }
        if (page != null) {
            if (page.keyset()) {
                meta.put("size", page.size());
                meta.put("hasNext", result.hasNext());
                List<Map<String, Object>> rows = result.rows();
                meta.put("nextCursor", Boolean.TRUE.equals(result.hasNext()) && !rows.isEmpty()
                        ? KeysetCursor.after(page.keys(), result.columns(), rows.get(rows.size() - 1)) : null);
            } else {
                meta.put("page", page.page());
                meta.put("size", page.size());
                if (result.total() != null) {
                    meta.put("total", result.total());
                }
                meta.put("hasNext", result.hasNext());
            }
        }
        if (result.truncated()) {
            meta.put("truncated", true);
        }
        if (result.affectedRows() != null) {
            return new Parts(Map.of("affectedRows", result.affectedRows()), meta, result.affectedRows());
        }
        masking.accept(result);
        return new Parts(ResultMapper.map(def.output(), result.rows()), meta, result.rows().size());
    }
}
