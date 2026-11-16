package io.opendbapi.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Who did what (spec §4.5). Never stores secrets or passwords in {@code detail}. */
@Repository
public class AuditLog {

    public record Entry(
            long id,
            OffsetDateTime ts,
            UUID workspaceId,
            UUID userId,
            String username,
            String action,
            String targetType,
            String targetId,
            JsonNode detail) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditLog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID workspaceId, UUID userId, String action, String targetType, String targetId,
                       Map<String, ?> detail) {
        jdbc.sql("""
                insert into audit_log (workspace_id, user_id, action, target_type, target_id, detail)
                values (?, ?, ?, ?, ?, ?::jsonb)""")
                .params(workspaceId, userId, action, targetType, targetId, write(detail))
                .update();
    }

    /** Newest first. {@code workspaceId} null means every workspace and system events. */
    public List<Entry> list(UUID workspaceId, String action, int limit, Long beforeId) {
        StringBuilder sql = new StringBuilder("""
                select a.*, u.username from audit_log a left join users u on u.id = a.user_id where true""");
        List<Object> params = new ArrayList<>();
        if (workspaceId != null) {
            sql.append(" and a.workspace_id = ?");
            params.add(workspaceId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" and a.action like ?");
            params.add(action.replace("%", "\\%") + "%");
        }
        if (beforeId != null) {
            sql.append(" and a.id < ?");
            params.add(beforeId);
        }
        sql.append(" order by a.id desc limit ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> {
            try {
                return new Entry(
                        rs.getLong("id"),
                        rs.getObject("ts", OffsetDateTime.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        json.readTree(rs.getString("detail")));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).list();
    }

    private String write(Map<String, ?> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
