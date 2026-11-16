package io.opendbapi.core.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipStore {

    /** A user's membership as shown in {@code /me}. */
    public record ActiveMembership(UUID id, String slug, String name, WorkspaceRole role) {
    }

    /** A workspace member as shown on the Members page. */
    public record Member(
            UUID userId,
            String username,
            String displayName,
            String email,
            String source,
            WorkspaceRole role,
            boolean fromMapping,
            OffsetDateTime createdAt) {
    }

    /** Raw row, for role synchronisation. */
    public record Row(UUID workspaceId, WorkspaceRole role, boolean fromMapping) {
    }

    private final JdbcClient jdbc;

    public MembershipStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActiveMembership> activeWorkspacesOf(UUID userId) {
        return jdbc.sql("""
                select w.id, w.slug, w.name, m.role
                from workspace_member m join workspace w on w.id = m.workspace_id
                where m.user_id = ? and w.status = 'active'
                order by lower(w.name)""")
                .param(userId)
                .query((rs, i) -> new ActiveMembership(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        WorkspaceRole.fromDb(rs.getString("role"))))
                .list();
    }

    public Optional<WorkspaceRole> roleOf(UUID workspaceId, UUID userId) {
        return jdbc.sql("select role from workspace_member where workspace_id = ? and user_id = ?")
                .params(workspaceId, userId)
                .query((rs, i) -> WorkspaceRole.fromDb(rs.getString(1)))
                .optional();
    }

    public List<Member> members(UUID workspaceId) {
        return jdbc.sql("""
                select u.id, u.username, u.display_name, u.email, u.source, m.role, m.from_mapping, m.created_at
                from workspace_member m join users u on u.id = m.user_id
                where m.workspace_id = ?
                order by lower(coalesce(u.display_name, u.username))""")
                .param(workspaceId)
                .query((rs, i) -> new Member(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("source"),
                        WorkspaceRole.fromDb(rs.getString("role")),
                        rs.getBoolean("from_mapping"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public List<Row> rowsOf(UUID userId) {
        return jdbc.sql("select workspace_id, role, from_mapping from workspace_member where user_id = ?")
                .param(userId)
                .query((rs, i) -> new Row(
                        rs.getObject("workspace_id", UUID.class),
                        WorkspaceRole.fromDb(rs.getString("role")),
                        rs.getBoolean("from_mapping")))
                .list();
    }

    public void upsert(UUID workspaceId, UUID userId, WorkspaceRole role, boolean fromMapping) {
        jdbc.sql("""
                insert into workspace_member (workspace_id, user_id, role, from_mapping) values (?, ?, ?, ?)
                on conflict (workspace_id, user_id) do update set role = excluded.role, from_mapping = excluded.from_mapping""")
                .params(workspaceId, userId, role.dbValue(), fromMapping)
                .update();
    }

    public boolean remove(UUID workspaceId, UUID userId) {
        return jdbc.sql("delete from workspace_member where workspace_id = ? and user_id = ?")
                .params(workspaceId, userId)
                .update() > 0;
    }

    public int countOwners(UUID workspaceId) {
        return jdbc.sql("select count(*) from workspace_member where workspace_id = ? and role = 'owner'")
                .param(workspaceId)
                .query(Integer.class)
                .single();
    }
}
