package io.opendbapi.core.endpoint;

import io.opendbapi.core.error.ApiProblem;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Endpoint groups: display folders, never part of a URL (spec §4.1). Row-level secured. */
@Repository
public class ApiGroupStore {

    public record ApiGroup(UUID id, String name, int endpointCount) {
    }

    private final JdbcClient jdbc;

    public ApiGroupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiGroup> list() {
        return jdbc.sql("""
                select g.id, g.name, count(e.id) filter (where e.deleted_at is null) as endpoints
                from api_group g left join endpoint e on e.group_id = g.id
                group by g.id, g.name order by lower(g.name)""")
                .query((rs, i) -> new ApiGroup(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("endpoints")))
                .list();
    }

    public ApiGroup create(UUID workspaceId, String name) {
        try {
            UUID id = jdbc.sql("insert into api_group (workspace_id, name) values (?, ?) returning id")
                    .params(workspaceId, name).query(UUID.class).single();
            return new ApiGroup(id, name, 0);
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("name_taken", "A group named '%s' already exists.".formatted(name));
        }
    }

    public boolean rename(UUID id, String name) {
        try {
            return jdbc.sql("update api_group set name = ? where id = ?").params(name, id).update() > 0;
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("name_taken", "A group named '%s' already exists.".formatted(name));
        }
    }

    /** Endpoints of the group (trashed included) are moved out of it. */
    public boolean delete(UUID id) {
        jdbc.sql("update endpoint set group_id = null where group_id = ?").param(id).update();
        return jdbc.sql("delete from api_group where id = ?").param(id).update() > 0;
    }
}
