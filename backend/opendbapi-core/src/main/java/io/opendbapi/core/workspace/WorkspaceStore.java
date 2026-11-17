package io.opendbapi.core.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Workspaces are a system-level table, not row-level secured (spec §4.0). */
@Repository
public class WorkspaceStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public WorkspaceStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Workspace create(String slug, String name) {
        try {
            return jdbc.sql("insert into workspace (slug, name) values (?, ?) returning *")
                    .params(slug, name)
                    .query(this::map)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("slug_taken", "A workspace with slug '%s' already exists.".formatted(slug));
        }
    }

    public Optional<Workspace> findBySlug(String slug) {
        return jdbc.sql("select * from workspace where slug = ?").param(slug).query(this::map).optional();
    }

    public Optional<Workspace> findById(UUID id) {
        return jdbc.sql("select * from workspace where id = ?").param(id).query(this::map).optional();
    }

    public List<Workspace> list() {
        return jdbc.sql("select * from workspace order by status, lower(name)").query(this::map).list();
    }

    public Workspace update(UUID id, String name, Map<String, Object> quotas) {
        return jdbc.sql("""
                update workspace set name = coalesce(?, name), quotas = coalesce(?::jsonb, quotas)
                where id = ? returning *""")
                .params(name, quotas == null ? null : write(quotas), id)
                .query(this::map)
                .single();
    }

    public Workspace setConfigLocked(UUID id, boolean locked) {
        return jdbc.sql("update workspace set config_locked = ? where id = ? returning *").params(locked, id).query(this::map).single();
    }

    public Workspace setArchived(UUID id, boolean archived) {
        return jdbc.sql("""
                update workspace
                set status = case when ? then 'archived' else 'active' end,
                    archived_at = case when ? then now() else null end
                where id = ? returning *""")
                .params(archived, archived, id)
                .query(this::map)
                .single();
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Workspace map(ResultSet rs, int row) throws SQLException {
        try {
            return new Workspace(
                    rs.getObject("id", UUID.class),
                    rs.getString("slug"),
                    rs.getString("name"),
                    rs.getString("status"),
                    json.readValue(rs.getString("quotas"), MAP),
                    rs.getObject("archived_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getBoolean("config_locked"));
        } catch (JsonProcessingException e) {
            throw new SQLException("Bad quotas json", e);
        }
    }
}
