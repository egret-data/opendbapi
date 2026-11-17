package io.opendbapi.core.alert;

import io.opendbapi.core.error.ApiProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Alert channels of the current workspace (row-level secured). The URL never leaves as text. */
@Repository
public class AlertChannelStore {

    /** What the API shows: the URL's host only, never the URL. */
    public record AlertChannel(UUID id, String name,
                               @Schema(allowableValues = {"slack", "teams", "google_chat", "webhook"}) String kind,
                               @Schema(description = "Host the webhook posts to") String urlHost,
                               boolean enabled,
                               @Schema(nullable = true) Instant lastSentAt,
                               @Schema(nullable = true, description = "Why the last delivery failed; null when it worked") String lastError,
                               Instant createdAt) {
    }

    /** A channel with its sealed URL reference, for sending. */
    public record ChannelTarget(UUID id, String name, String kind, String urlRef) {
    }

    private final JdbcClient jdbc;

    public AlertChannelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlertChannel> list() {
        return jdbc.sql("select * from alert_channel order by lower(name)").query(AlertChannelStore::row).list();
    }

    public Optional<AlertChannel> find(UUID id) {
        return jdbc.sql("select * from alert_channel where id = ?").param(id).query(AlertChannelStore::row).optional();
    }

    public List<ChannelTarget> enabledTargets() {
        return jdbc.sql("select id, name, kind, url_ref from alert_channel where enabled order by name")
                .query((rs, i) -> new ChannelTarget(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    public Optional<ChannelTarget> target(UUID id) {
        return jdbc.sql("select id, name, kind, url_ref from alert_channel where id = ?").param(id)
                .query((rs, i) -> new ChannelTarget(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
    }

    public UUID create(UUID workspaceId, String name, String kind, String urlRef, String urlHost, boolean enabled, UUID userId) {
        try {
            return jdbc.sql("""
                    insert into alert_channel (workspace_id, name, kind, url_ref, url_host, enabled, created_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(workspaceId, name, kind, urlRef, urlHost, enabled, userId).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    /** @param urlRef null keeps the current URL */
    public boolean update(UUID id, String name, String kind, String urlRef, String urlHost, boolean enabled) {
        try {
            return jdbc.sql("""
                    update alert_channel set name = ?, kind = ?, enabled = ?,
                           url_ref = coalesce(?, url_ref), url_host = coalesce(?, url_host) where id = ?""")
                    .params(name, kind, enabled, urlRef, urlHost, id).update() > 0;
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from alert_channel where id = ?").param(id).update() > 0;
    }

    public void delivered(UUID id, String error) {
        jdbc.sql("update alert_channel set last_sent_at = now(), last_error = ? where id = ?").params(error, id).update();
    }

    private static AlertChannel row(ResultSet rs, int i) throws SQLException {
        return new AlertChannel(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kind"), rs.getString("url_host"),
                rs.getBoolean("enabled"), ts(rs, "last_sent_at"), rs.getString("last_error"), ts(rs, "created_at"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ApiProblem nameTaken(String name) {
        return ApiProblem.conflict("name_taken", "An alert channel named '%s' already exists.".formatted(name));
    }
}
