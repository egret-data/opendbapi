package io.opendbapi.core.datapolicy;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendbapi.core.client.ServiceAccountStore;
import io.opendbapi.core.endpoint.QueryExecutor.ResultColumn;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.sql.policy.ColumnLineage;
import io.opendbapi.sql.policy.Masks;
import io.opendbapi.sql.policy.RowFilters;
import io.opendbapi.sql.template.BoundSql;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies data policies on the serving path (spec §12, §9.1 steps 11 and 13): the row filter
 * rewrites the main statement before pagination, masks change values after execution.
 * Policies are cached per workspace for a few seconds; changes on any instance clear it everywhere (ChangeBus).
 * Everything fails closed: a row filter that cannot be resolved stops the call.
 */
@Component
public class DataPolicies {

    private static final Logger log = LoggerFactory.getLogger(DataPolicies.class);
    private static final Duration TTL = Duration.ofSeconds(5);
    private static final Pattern ATTRIBUTE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern TABLE_COLUMN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,127}(\\.[A-Za-z_][A-Za-z0-9_$]{0,127}){0,2}$");

    private record Cached(List<DataPolicyStore.Stored> policies, long expiresAtNanos) {
    }

    private final WorkspaceScope scope;
    private final DataPolicyStore store;
    private final ServiceAccountStore accounts;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ColumnLineage.Lineage>> lineages = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<ColumnLineage.Lineage>> eldest) {
                    return size() > 512;
                }
            });

    private io.opendbapi.core.endpoint.ResultCache results;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setResults(io.opendbapi.core.endpoint.ResultCache results) {
        this.results = results;
    }

    public DataPolicies(WorkspaceScope scope, DataPolicyStore store, ServiceAccountStore accounts) {
        this.scope = scope;
        this.store = store;
        this.accounts = accounts;
    }

    /** Policies or caller attributes changed: reload them, and drop cached answers built with the old ones. */
    public void invalidate(UUID workspaceId) {
        if (changes != null) {
            changes.publish("policies", workspaceId);
        } else {
            cache.remove(workspaceId);
        }
        if (results != null) {
            results.clearWorkspace(workspaceId);
        }
    }

    private io.opendbapi.core.cluster.ChangeBus changes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChanges(io.opendbapi.core.cluster.ChangeBus changes) {
        this.changes = changes;
        changes.on("policies", ws -> {
            if (ws == null) {
                cache.clear();
            } else {
                cache.remove(ws);
            }
        });
    }

    /** Checks a policy before it is saved; the message is for workspace admins. */
    public static void check(DataPolicy p) {
        boolean mask = DataPolicy.MASK.equals(p.kind());
        if (!mask && !DataPolicy.ROW_FILTER.equals(p.kind())) {
            throw ApiProblem.badRequest("invalid_policy", "kind must be mask or row_filter.");
        }
        if ((p.datasourceId() == null) == (p.endpointId() == null)) {
            throw ApiProblem.badRequest("invalid_policy", "Give either a datasource or an endpoint.");
        }
        String column = p.column() == null ? "" : p.column().strip();
        if (mask) {
            if (p.mask() == null) {
                throw ApiProblem.badRequest("invalid_policy", "A mask needs a mask type.");
            }
            Masks.MaskStyle type = maskType(p.mask().type());
            if (type == Masks.MaskStyle.partial) {
                int start = p.mask().keepStart() == null ? -1 : p.mask().keepStart();
                int end = p.mask().keepEnd() == null ? -1 : p.mask().keepEnd();
                if (start < 0 || end < 0 || start > 64 || end > 64) {
                    throw ApiProblem.badRequest("invalid_policy", "A partial mask keeps 0 to 64 characters at each end.");
                }
            }
            boolean valid = p.datasourceId() != null ? TABLE_COLUMN.matcher(column).matches()
                    : !column.isEmpty() && column.length() <= 128 && column.chars().noneMatch(Character::isISOControl);
            if (!valid) {
                throw ApiProblem.badRequest("invalid_policy", p.datasourceId() != null
                        ? "Name the column as column, table.column or schema.table.column."
                        : "Name the result column as the endpoint returns it.");
            }
        } else {
            if (p.endpointId() == null) {
                throw ApiProblem.badRequest("invalid_policy", "A row filter belongs to an endpoint.");
            }
            if (!RowFilters.validColumn(column)) {
                throw ApiProblem.badRequest("invalid_policy", "The filtered column must be a plain name: letters, digits and _.");
            }
            if (p.attribute() == null || !ATTRIBUTE.matcher(p.attribute()).matches()) {
                throw ApiProblem.badRequest("invalid_policy", "Name the service account attribute that holds the allowed value.");
            }
        }
        if (p.exemptServiceAccounts() != null && p.exemptServiceAccounts().size() > 100) {
            throw ApiProblem.badRequest("invalid_policy", "At most 100 exempt service accounts.");
        }
    }

    static Masks.MaskStyle maskType(String type) {
        try {
            return Masks.MaskStyle.valueOf(type);
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("invalid_policy", "Mask type must be phone, email, partial, hash or nullify.");
        }
    }

    /** Policies that matter for one endpoint: its own and its datasource's. */
    public List<DataPolicyStore.Stored> forEndpoint(UUID workspaceId, UUID endpointId, UUID datasourceId) {
        List<DataPolicyStore.Stored> out = new ArrayList<>();
        for (DataPolicyStore.Stored s : policies(workspaceId)) {
            DataPolicy p = s.policy();
            if (endpointId.equals(p.endpointId()) || (datasourceId != null && datasourceId.equals(p.datasourceId()))) {
                out.add(s);
            }
        }
        return out;
    }

    private List<DataPolicyStore.Stored> policies(UUID workspaceId) {
        Cached c = cache.get(workspaceId);
        if (c == null || System.nanoTime() > c.expiresAtNanos()) {
            c = new Cached(scope.call(workspaceId, store::all), System.nanoTime() + TTL.toNanos());
            cache.put(workspaceId, c);
        }
        return c.policies();
    }

    /**
     * What to do for one call. {@code serviceAccountId} is null for an anonymous call.
     *
     * @throws ApiProblem 403 when a row filter applies and the caller has no value for it
     */
    public Plan plan(UUID workspaceId, UUID endpointId, UUID datasourceId, UUID serviceAccountId) {
        List<DataPolicyStore.Stored> applicable = forEndpoint(workspaceId, endpointId, datasourceId).stream()
                .filter(s -> s.policy().appliesTo(serviceAccountId)).toList();
        if (applicable.isEmpty()) {
            return Plan.NONE;
        }
        RowFilter filter = null;
        List<DataPolicyStore.Stored> masks = new ArrayList<>();
        for (DataPolicyStore.Stored s : applicable) {
            if (DataPolicy.ROW_FILTER.equals(s.policy().kind())) {
                filter = rowFilter(workspaceId, s.policy(), serviceAccountId);
            } else {
                masks.add(s);
            }
        }
        return new Plan(filter, masks, this);
    }

    private record RowFilter(String column, List<Object> values) {
    }

    private RowFilter rowFilter(UUID workspaceId, DataPolicy p, UUID serviceAccountId) {
        if (serviceAccountId == null) {
            throw new ApiProblem(403, "row_filter_needs_client",
                    "This endpoint returns only the caller's own rows; call it with a client's access token.");
        }
        JsonNode attributes = scope.call(workspaceId, () -> accounts.attributes(serviceAccountId));
        JsonNode value = attributes == null ? null : attributes.get(p.attribute());
        if (value == null || value.isNull()) {
            throw new ApiProblem(403, "missing_attribute",
                    "The calling service account has no '%s' attribute, which this endpoint filters rows by.".formatted(p.attribute()));
        }
        List<Object> values = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(v -> values.add(scalar(v)));
        } else {
            values.add(scalar(value));
        }
        return new RowFilter(p.column(), values);
    }

    private static Object scalar(JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.longValue();
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return v.asText();
    }

    Optional<ColumnLineage.Lineage> lineage(String sql) {
        return lineages.computeIfAbsent(sql, ColumnLineage::trace);
    }

    /** One call's policies. Not thread-safe: a call runs on one thread. */
    public static final class Plan implements io.opendbapi.core.endpoint.QueryExecutor.Policy {

        @Override
        public BoundSql rewrite(BoundSql query) {
            return rewriteMain(query);
        }

        @Override
        public void mask(List<ResultColumn> columns, List<Map<String, Object>> rows) {
            apply(masksFor(columns), columns, rows);
        }


        public static final Plan NONE = new Plan(null, List.of(), null);

        private final RowFilter filter;
        private final List<DataPolicyStore.Stored> masks;
        private final DataPolicies owner;
        private String mainSql;

        private Plan(RowFilter filter, List<DataPolicyStore.Stored> masks, DataPolicies owner) {
            this.filter = filter;
            this.masks = masks;
            this.owner = owner;
        }

        public boolean active() {
            return filter != null || !masks.isEmpty();
        }

        /** Applies the row filter to the main statement, before pagination. */
        public BoundSql rewriteMain(BoundSql main) {
            mainSql = main.sql();
            if (filter == null) {
                return main;
            }
            try {
                return RowFilters.apply(main, filter.column(), filter.values());
            } catch (RowFilters.UnsupportedException | IllegalArgumentException e) {
                log.warn("Row filter on '{}' cannot be applied: {}", filter.column(), e.getMessage());
                throw new ApiProblem(500, "row_filter_failed",
                        "This endpoint's row filter cannot be applied to its query; its owner has to fix the endpoint.");
            }
        }

        /** One mask (or null) per result column, by position; null when nothing is masked. */
        public Masks.Masker[] masksFor(List<ResultColumn> columns) {
            if (masks.isEmpty()) {
                return null;
            }
            Optional<ColumnLineage.Lineage> lineage = Optional.empty();
            boolean needsLineage = masks.stream().anyMatch(s -> s.policy().datasourceId() != null);
            if (needsLineage && mainSql != null) {
                lineage = owner.lineage(mainSql);
            }
            Masks.Masker[] out = new Masks.Masker[columns.size()];
            boolean any = false;
            for (int i = 0; i < columns.size(); i++) {
                String label = columns.get(i).name();
                Optional<ColumnLineage.TracedColumn> traced = lineage.flatMap(l -> l.column(label));
                for (DataPolicyStore.Stored s : masks) {
                    if (matches(s.policy(), label, traced)) {
                        DataPolicy.Mask m = s.policy().mask();
                        out[i] = new Masks.Masker(maskType(m.type()), m.keepStart() == null ? 0 : m.keepStart(),
                                m.keepEnd() == null ? 0 : m.keepEnd(), s.salt());
                        any = true;
                        break;
                    }
                }
            }
            return any ? out : null;
        }

        /** Applies masks in place to a row given by position. */
        public static void apply(Masks.Masker[] masks, Object[] values) {
            if (masks == null) {
                return;
            }
            for (int i = 0; i < masks.length && i < values.length; i++) {
                if (masks[i] != null) {
                    values[i] = masks[i].apply(values[i]);
                }
            }
        }

        /** Applies masks in place to rows keyed by column label. */
        public static void apply(Masks.Masker[] masks, List<ResultColumn> columns, List<Map<String, Object>> rows) {
            if (masks == null) {
                return;
            }
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < masks.length; i++) {
                    if (masks[i] != null) {
                        String name = columns.get(i).name();
                        row.put(name, masks[i].apply(row.get(name)));
                    }
                }
            }
        }
    }

    /**
     * An endpoint mask names a result column. A datasource mask names a table column: it covers
     * any result column computed from it, and, as a fallback that errs towards masking, any
     * result column with the same name.
     */
    static boolean matches(DataPolicy p, String label, Optional<ColumnLineage.TracedColumn> traced) {
        if (p.endpointId() != null) {
            return p.column().equalsIgnoreCase(label);
        }
        String[] parts = p.column().split("\\.");
        String column = parts[parts.length - 1];
        String table = parts.length >= 2 ? parts[parts.length - 2] : null;
        if (column.equalsIgnoreCase(label)) {
            return true;
        }
        return traced.map(o -> o.sources().stream().anyMatch(s -> s.column().equalsIgnoreCase(column)
                && (table == null || s.table() == null || lastPart(s.table()).equalsIgnoreCase(table)))).orElse(false);
    }

    private static String lastPart(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }
}
