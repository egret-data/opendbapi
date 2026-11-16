package io.opendbapi.core.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Datasources live in a row-level-secured table: call only inside
 * {@link io.opendbapi.core.workspace.WorkspaceScope}, which limits every query to one workspace.
 */
@Repository
public class DatasourceStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DatasourceStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Datasource> list() {
        return jdbc.sql("select * from datasource order by lower(name)").query(this::map).list();
    }

    public Optional<Datasource> find(UUID id) {
        return jdbc.sql("select * from datasource where id = ?").param(id).query(this::map).optional();
    }

    public int count() {
        return jdbc.sql("select count(*) from datasource").query(Integer.class).single();
    }

    public Datasource create(UUID workspaceId, String name, String description, JdbcConfig config, String secretRef,
                             boolean readOnly, UUID createdBy) {
        try {
            return jdbc.sql("""
                    insert into datasource (workspace_id, name, description, type, config, secret_ref, read_only, created_by)
                    values (?, ?, ?, 'jdbc', ?::jsonb, ?, ?, ?) returning *""")
                    .params(workspaceId, name, description, write(config), secretRef, readOnly, createdBy)
                    .query(this::map)
                    .single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    /** {@code secretRef == null} keeps the stored password. */
    public Datasource update(UUID id, String name, String description, JdbcConfig config, String secretRef, boolean readOnly) {
        try {
            return jdbc.sql("""
                    update datasource set name = ?, description = ?, config = ?::jsonb,
                           secret_ref = coalesce(?, secret_ref), read_only = ?, updated_at = now()
                    where id = ? returning *""")
                    .params(name, description, write(config), secretRef, readOnly, id)
                    .query(this::map)
                    .single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from datasource where id = ?").param(id).update() > 0;
    }

    /** Names of endpoints (draft or published, not trashed) whose task uses this datasource. */
    public List<String> endpointsUsing(UUID id) {
        return jdbc.sql("""
                select e.name from endpoint e
                where e.deleted_at is null
                  and (e.definition -> 'task' ->> 'datasourceId' = ?
                       or exists (select 1 from endpoint_version v
                                  where v.endpoint_id = e.id and v.id = e.live_version_id
                                    and v.definition -> 'task' ->> 'datasourceId' = ?))
                order by e.name""")
                .params(id.toString(), id.toString())
                .query(String.class)
                .list();
    }

    private static ApiProblem nameTaken(String name) {
        return ApiProblem.conflict("name_taken", "A datasource named '%s' already exists in this workspace.".formatted(name));
    }

    private String write(JdbcConfig config) {
        try {
            return json.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Datasource map(ResultSet rs, int row) throws SQLException {
        try {
            return new Datasource(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("type"),
                    json.readValue(rs.getString("config"), JdbcConfig.class),
                    rs.getString("secret_ref"),
                    rs.getBoolean("read_only"),
                    rs.getObject("created_by", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new SQLException("Bad datasource config", e);
        }
    }
}
