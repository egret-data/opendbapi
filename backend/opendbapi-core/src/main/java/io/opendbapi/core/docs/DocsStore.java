package io.opendbapi.core.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.endpoint.EndpointVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Live endpoints with what their documentation needs (spec §10). Row-level secured. */
@Repository
public class DocsStore {

    public record DocEntry(UUID endpointId, String name, String description, String group, int version,
                           EndpointVersion.Snapshot snapshot, JsonNode outputSchema) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DocsStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<DocEntry> live(UUID endpointIdOrNull) {
        return jdbc.sql("""
                select e.id, e.name, e.description, g.name as group_name, v.version, v.definition, v.output_schema
                from endpoint e join endpoint_version v on v.id = e.live_version_id
                left join api_group g on g.id = e.group_id
                where e.state = 'live' and e.deleted_at is null and (?::uuid is null or e.id = ?::uuid)
                order by lower(coalesce(g.name, '')), e.path""")
                .params(endpointIdOrNull, endpointIdOrNull)
                .query((rs, i) -> {
                    try {
                        return new DocEntry(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                                rs.getString("group_name"), rs.getInt("version"),
                                json.readValue(rs.getString("definition"), EndpointVersion.Snapshot.class),
                                json.readTree(rs.getString("output_schema")));
                    } catch (java.io.IOException e) {
                        throw new java.sql.SQLException("Unreadable endpoint version", e);
                    }
                })
                .list();
    }
}
