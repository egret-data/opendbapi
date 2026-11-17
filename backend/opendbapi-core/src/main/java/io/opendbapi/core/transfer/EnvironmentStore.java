package io.opendbapi.core.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.error.ApiProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Environments of a workspace (spec §15.2); call inside the workspace scope. */
@Component
public class EnvironmentStore {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,62}$");

    public record ConfigEnvironment(String name,
                                    @Schema(description = "Logical datasource name in configuration files → datasource name in this workspace")
                                    Map<String, String> bindings,
                                    Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public EnvironmentStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ConfigEnvironment> list() {
        return jdbc.sql("select name, bindings::text, updated_at from environment order by name").query((rs, i) -> map(rs)).list();
    }

    public Optional<ConfigEnvironment> find(String name) {
        return jdbc.sql("select name, bindings::text, updated_at from environment where name = ?").param(name)
                .query((rs, i) -> map(rs)).optional();
    }

    /** Creates or replaces the environment. */
    public void put(UUID workspaceId, String name, Map<String, String> bindings) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ApiProblem.badRequest("invalid_environment",
                    "Name the environment with lower-case letters, digits, '.', '_' and '-' (at most 63).");
        }
        Map<String, String> clean = new TreeMap<>();
        (bindings == null ? Map.<String, String>of() : bindings).forEach((k, v) -> {
            if (k == null || k.isBlank() || v == null || v.isBlank()) {
                throw ApiProblem.badRequest("invalid_environment", "Every binding needs a logical name and a datasource name.");
            }
            clean.put(k.strip(), v.strip());
        });
        try {
            jdbc.sql("""
                    insert into environment (workspace_id, name, bindings) values (?, ?, ?::jsonb)
                    on conflict (workspace_id, name) do update set bindings = excluded.bindings, updated_at = now()""")
                    .params(workspaceId, name, json.writeValueAsString(clean)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean delete(String name) {
        return jdbc.sql("delete from environment where name = ?").param(name).update() > 0;
    }

    private ConfigEnvironment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ConfigEnvironment(rs.getString(1), json.readValue(rs.getString(2), new TypeReference<TreeMap<String, String>>() {
            }), rs.getTimestamp(3).toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
