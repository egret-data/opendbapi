package io.opendbapi.core.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Endpoints and versions; row-level secured, so call inside {@code WorkspaceScope}. */
@Repository
public class EndpointStore {

    private static final String SELECT = """
            select e.*, v.version as live_version from endpoint e
            left join endpoint_version v on v.id = e.live_version_id""";

    /** A live endpoint with its serving snapshot, for the registry. */
    public record LiveRow(UUID endpointId, UUID versionId, int version, EndpointVersion.Snapshot snapshot) {
    }

    public record Filter(String state, UUID groupId, UUID datasourceId, String query, boolean trashed) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public EndpointStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Endpoint> list(Filter filter) {
        StringBuilder sql = new StringBuilder(SELECT).append(filter.trashed() ? " where e.deleted_at is not null" : " where e.deleted_at is null");
        List<Object> params = new ArrayList<>();
        if (filter.state() != null) {
            sql.append(" and e.state = ?");
            params.add(filter.state());
        }
        if (filter.groupId() != null) {
            sql.append(" and e.group_id = ?");
            params.add(filter.groupId());
        }
        if (filter.datasourceId() != null) {
            sql.append(" and e.definition -> 'task' ->> 'datasourceId' = ?");
            params.add(filter.datasourceId().toString());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" and (lower(e.name) like ? or lower(e.path) like ?)");
            String like = "%" + filter.query().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" order by lower(e.name)");
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public Optional<Endpoint> find(UUID id) {
        return jdbc.sql(SELECT + " where e.id = ?").param(id).query(this::map).optional();
    }

    public boolean routeTaken(String method, String path) {
        return jdbc.sql("select exists(select 1 from endpoint where method = ? and path = ? and deleted_at is null)")
                .params(method, path).query(Boolean.class).single();
    }

    public Endpoint create(UUID workspaceId, UUID groupId, String name, String description, String method, String path,
                           String access, EndpointDefinition definition, UUID createdBy) {
        try {
            UUID id = jdbc.sql("""
                    insert into endpoint (workspace_id, group_id, name, description, method, path, access, definition, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""")
                    .params(workspaceId, groupId, name, description, method, path, access, write(definition), createdBy)
                    .query(UUID.class).single();
            return find(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw routeTakenProblem(method, path);
        } catch (DataIntegrityViolationException e) {
            throw invalidRow(e);
        }
    }

    /** Saves the draft; {@code dirty} becomes true unless nothing has been published. */
    public Endpoint updateDraft(UUID id, UUID groupId, String name, String description, String method, String path,
                                String access, EndpointDefinition definition) {
        try {
            jdbc.sql("""
                    update endpoint set group_id = ?, name = ?, description = ?, method = ?, path = ?, access = ?,
                           definition = ?::jsonb, dirty = true, updated_at = now()
                    where id = ?""")
                    .params(groupId, name, description, method, path, access, write(definition), id)
                    .update();
            return find(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw routeTakenProblem(method, path);
        } catch (DataIntegrityViolationException e) {
            throw invalidRow(e);
        }
    }

    public EndpointVersion publish(Endpoint e, JsonNode outputSchema, String note, UUID publishedBy) {
        int next = jdbc.sql("select coalesce(max(version), 0) + 1 from endpoint_version where endpoint_id = ?")
                .param(e.id()).query(Integer.class).single();
        EndpointVersion.Snapshot snapshot = new EndpointVersion.Snapshot(e.method(), e.path(), e.access(), e.definition());
        UUID versionId = jdbc.sql("""
                insert into endpoint_version (workspace_id, endpoint_id, version, definition, output_schema, note, published_by)
                values (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) returning id""")
                .params(e.workspaceId(), e.id(), next, write(snapshot), write(outputSchema), note, publishedBy)
                .query(UUID.class).single();
        jdbc.sql("update endpoint set live_version_id = ?, state = 'live', dirty = false, updated_at = now() where id = ?")
                .params(versionId, e.id()).update();
        return version(e.id(), next).orElseThrow();
    }

    public void setLive(UUID endpointId, UUID versionId, String state) {
        jdbc.sql("update endpoint set live_version_id = ?, state = ?, updated_at = now() where id = ?")
                .params(versionId, state, endpointId).update();
    }

    public void setState(UUID endpointId, String state) {
        jdbc.sql("update endpoint set state = ?, updated_at = now() where id = ?").params(state, endpointId).update();
    }

    public void setTrashed(UUID endpointId, boolean trashed) {
        jdbc.sql("update endpoint set deleted_at = case when ? then now() else null end, updated_at = now() where id = ?")
                .params(trashed, endpointId).update();
    }

    public boolean delete(UUID endpointId) {
        return jdbc.sql("delete from endpoint where id = ? and deleted_at is not null").param(endpointId).update() > 0;
    }

    public List<EndpointVersion> versions(UUID endpointId) {
        return jdbc.sql("""
                select v.*, u.username as published_by_name from endpoint_version v
                left join users u on u.id = v.published_by
                where v.endpoint_id = ? order by v.version desc""")
                .param(endpointId).query(this::mapVersion).list();
    }

    public Optional<EndpointVersion> version(UUID endpointId, int version) {
        return jdbc.sql("""
                select v.*, u.username as published_by_name from endpoint_version v
                left join users u on u.id = v.published_by
                where v.endpoint_id = ? and v.version = ?""")
                .params(endpointId, version).query(this::mapVersion).optional();
    }

    /** Everything this workspace serves right now. */
    public List<LiveRow> live() {
        return jdbc.sql("""
                select e.id as endpoint_id, v.id as version_id, v.version, v.definition
                from endpoint e join endpoint_version v on v.id = e.live_version_id
                where e.state = 'live' and e.deleted_at is null""")
                .query((rs, i) -> new LiveRow(rs.getObject("endpoint_id", UUID.class), rs.getObject("version_id", UUID.class),
                        rs.getInt("version"), read(rs.getString("definition"), EndpointVersion.Snapshot.class)))
                .list();
    }

    private static ApiProblem routeTakenProblem(String method, String path) {
        return ApiProblem.conflict("route_taken", "Another endpoint in this workspace already answers %s /%s.".formatted(method, path));
    }

    private static ApiProblem invalidRow(DataIntegrityViolationException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("endpoint_path_check")) {
            return ApiProblem.badRequest("invalid_path", "A path is segments of letters, digits, _ and -, or {param}, separated by /.");
        }
        if (m.contains("api_group")) {
            return ApiProblem.badRequest("unknown_group", "That group does not exist in this workspace.");
        }
        return ApiProblem.badRequest("invalid_endpoint", "The endpoint could not be saved: " + m);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored endpoint JSON is unreadable", e);
        }
    }

    private Endpoint map(ResultSet rs, int row) throws SQLException {
        int live = rs.getInt("live_version");
        Integer liveVersion = rs.wasNull() ? null : live;
        return new Endpoint(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getString("method"), rs.getString("path"),
                rs.getString("state"), rs.getObject("live_version_id", UUID.class), liveVersion,
                rs.getString("access"), read(rs.getString("definition"), EndpointDefinition.class), rs.getBoolean("dirty"),
                rs.getObject("deleted_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private EndpointVersion mapVersion(ResultSet rs, int row) throws SQLException {
        try {
            return new EndpointVersion(
                    rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class), rs.getInt("version"),
                    read(rs.getString("definition"), EndpointVersion.Snapshot.class), json.readTree(rs.getString("output_schema")),
                    rs.getString("note"), rs.getObject("published_by", UUID.class), rs.getString("published_by_name"),
                    rs.getObject("published_at", OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
    }
}
