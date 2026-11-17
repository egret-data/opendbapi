package io.opendbapi.core.datapolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.core.error.ApiProblem;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data policies of the current workspace (row-level secured). The hash salt never leaves here. */
@Repository
public class DataPolicyStore {

    /** A policy with the salt its {@code hash} mask uses. */
    public record Stored(DataPolicy policy, byte[] salt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DataPolicyStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String SELECT = """
            select id, kind, datasource_id, endpoint_id, column_name, rule, created_at, updated_at from data_policy""";

    public List<Stored> all() {
        return jdbc.sql(SELECT + " order by kind, lower(column_name), created_at").query(this::row).list();
    }

    public List<DataPolicy> list(UUID datasourceId, UUID endpointId) {
        List<DataPolicy> out = new ArrayList<>();
        for (Stored s : all()) {
            DataPolicy p = s.policy();
            if ((datasourceId == null || datasourceId.equals(p.datasourceId())) && (endpointId == null || endpointId.equals(p.endpointId()))) {
                out.add(p);
            }
        }
        return out;
    }

    public Optional<DataPolicy> find(UUID id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query(this::row).optional().map(Stored::policy);
    }

    public UUID create(UUID workspaceId, DataPolicy p, UUID userId) {
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        try {
            return jdbc.sql("""
                    insert into data_policy (workspace_id, datasource_id, endpoint_id, kind, column_name, rule, created_by)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?) returning id""")
                    .params(workspaceId, p.datasourceId(), p.endpointId(), p.kind(), p.column(), rule(p, salt), userId)
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("row_filter_exists", "This endpoint already has a row filter; edit that one.");
        } catch (DataIntegrityViolationException e) {
            throw ApiProblem.notFound("resource_not_found", "That datasource or endpoint does not exist in this workspace.");
        }
    }

    /** Changes column, mask, attribute and exemptions; the salt is kept. */
    public boolean update(UUID id, DataPolicy p) {
        byte[] salt = jdbc.sql("select rule ->> 'salt' from data_policy where id = ?").param(id).query(String.class).optional()
                .map(s -> Base64.getDecoder().decode(s)).orElse(null);
        if (salt == null) {
            return false;
        }
        return jdbc.sql("update data_policy set column_name = ?, rule = ?::jsonb, updated_at = now() where id = ?")
                .params(p.column(), rule(p, salt), id).update() > 0;
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from data_policy where id = ?").param(id).update() > 0;
    }

    private String rule(DataPolicy p, byte[] salt) {
        ObjectNode rule = json.createObjectNode();
        if (p.mask() != null) {
            rule.put("type", p.mask().type());
            if (p.mask().keepStart() != null) {
                rule.put("keepStart", p.mask().keepStart());
            }
            if (p.mask().keepEnd() != null) {
                rule.put("keepEnd", p.mask().keepEnd());
            }
        }
        if (p.attribute() != null) {
            rule.put("attribute", p.attribute());
        }
        var exempt = rule.putArray("exempt");
        p.exemptServiceAccounts().forEach(u -> exempt.add(u.toString()));
        rule.put("salt", Base64.getEncoder().encodeToString(salt));
        return rule.toString();
    }

    private Stored row(ResultSet rs, int i) throws SQLException {
        JsonNode rule;
        try {
            rule = json.readTree(rs.getString("rule"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt data policy rule", e);
        }
        String kind = rs.getString("kind");
        DataPolicy.Mask mask = DataPolicy.MASK.equals(kind) ? new DataPolicy.Mask(rule.path("type").asText(),
                rule.hasNonNull("keepStart") ? rule.get("keepStart").asInt() : null,
                rule.hasNonNull("keepEnd") ? rule.get("keepEnd").asInt() : null) : null;
        List<UUID> exempt = new ArrayList<>();
        rule.path("exempt").forEach(n -> exempt.add(UUID.fromString(n.asText())));
        DataPolicy policy = new DataPolicy(rs.getObject("id", UUID.class), kind, rs.getObject("datasource_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getString("column_name"), mask,
                rule.hasNonNull("attribute") ? rule.get("attribute").asText() : null, List.copyOf(exempt),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        return new Stored(policy, Base64.getDecoder().decode(rule.path("salt").asText("")));
    }
}
