package io.opendbapi.core.secret;

import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Secrets OpenDBAPI keeps for a workspace (spec §4.5). A secret has a name and a value sealed
 * with the master key; the value never leaves the server. Datasource passwords and webhook URLs
 * refer to one as {@code secret:NAME}, so changing the value updates every user of it. Names
 * cannot change, because references hold them.
 */
@Component
public class WorkspaceSecrets {

    public static final String PREFIX = "secret:";
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,62}$");

    /** A secret as the API shows it: never the value. */
    public record ManagedSecret(String name, @Schema(nullable = true) String description, Instant createdAt, Instant updatedAt,
                                @Schema(description = "Datasources and alert channels that use it") List<SecretUse> usedBy) {
    }

    public record SecretUse(@Schema(allowableValues = {"datasource", "alert_channel"}) String kind, UUID id, String name) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceScope scope;
    private final SecretResolver secrets;

    public WorkspaceSecrets(JdbcClient jdbc, WorkspaceScope scope, SecretResolver secrets) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.secrets = secrets;
    }

    public static boolean isReference(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    public static String nameOf(String ref) {
        return ref.substring(PREFIX.length()).strip();
    }

    /**
     * The value behind any reference: {@code secret:NAME} in this workspace, or what
     * {@link SecretResolver} reads ({@code env:}, {@code enc:v1:}).
     */
    public String resolve(UUID workspaceId, String ref) {
        if (!isReference(ref)) {
            return secrets.resolve(ref);
        }
        String name = nameOf(ref);
        String sealed = scope.call(workspaceId, () -> jdbc.sql("select value_ref from workspace_secret where name = ?").param(name)
                .query(String.class).optional())
                .orElseThrow(() -> ApiProblem.badRequest("secret_unavailable", "Secret '%s' does not exist in this workspace.".formatted(name)));
        return secrets.resolve(sealed);
    }

    /**
     * Turns what an admin typed into what is stored: {@code secret:NAME} (checked to exist),
     * {@code env:NAME}, or plain text, which gets sealed. Call inside the workspace scope.
     */
    public String toReference(String input) {
        if (isReference(input)) {
            String name = nameOf(input);
            if (find(name).isEmpty()) {
                throw ApiProblem.badRequest("secret_unavailable", "Secret '%s' does not exist in this workspace.".formatted(name));
            }
            return PREFIX + name;
        }
        return secrets.toReference(input);
    }

    // --- management, inside the workspace scope ---

    public List<ManagedSecret> list() {
        return jdbc.sql("select name, description, created_at, updated_at from workspace_secret order by name")
                .query((rs, i) -> new ManagedSecret(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(), usedBy(rs.getString(1))))
                .list();
    }

    public Optional<ManagedSecret> find(String name) {
        return jdbc.sql("select name, description, created_at, updated_at from workspace_secret where name = ?").param(name)
                .query((rs, i) -> new ManagedSecret(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(), usedBy(rs.getString(1))))
                .optional();
    }

    public void create(UUID workspaceId, String name, String description, String value, UUID userId) {
        checkName(name);
        String sealed = seal(value);
        try {
            jdbc.sql("insert into workspace_secret (workspace_id, name, description, value_ref, created_by) values (?, ?, ?, ?, ?)")
                    .params(workspaceId, name, description, sealed, userId).update();
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("name_taken", "A secret named '%s' already exists.".formatted(name));
        }
    }

    /** @param value null keeps the value */
    public boolean update(String name, String description, String value) {
        String sealed = value == null ? null : seal(value);
        return jdbc.sql("""
                update workspace_secret set description = ?, value_ref = coalesce(?, value_ref), updated_at = now() where name = ?""")
                .params(description, sealed, name).update() > 0;
    }

    public boolean delete(String name) {
        List<SecretUse> uses = usedBy(name);
        if (!uses.isEmpty()) {
            throw ApiProblem.conflict("secret_in_use", "Still used by %s; point them elsewhere first."
                    .formatted(String.join(", ", uses.stream().map(u -> u.kind().replace('_', ' ') + " '" + u.name() + "'").toList())));
        }
        return jdbc.sql("delete from workspace_secret where name = ?").param(name).update() > 0;
    }

    /** Ids of the workspace's datasources whose password is this secret. */
    public List<UUID> datasourcesUsing(String name) {
        return jdbc.sql("select id from datasource where secret_ref = ?").param(PREFIX + name).query(UUID.class).list();
    }

    private List<SecretUse> usedBy(String name) {
        List<SecretUse> out = new ArrayList<>();
        String ref = PREFIX + name;
        out.addAll(jdbc.sql("select id, name from datasource where secret_ref = ? order by name").param(ref)
                .query((rs, i) -> new SecretUse("datasource", rs.getObject(1, UUID.class), rs.getString(2))).list());
        out.addAll(jdbc.sql("select id, name from alert_channel where url_ref = ? order by name").param(ref)
                .query((rs, i) -> new SecretUse("alert_channel", rs.getObject(1, UUID.class), rs.getString(2))).list());
        return out;
    }

    private String seal(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiProblem.badRequest("invalid_secret", "Give the secret's value.");
        }
        if (value.length() > 10_000) {
            throw ApiProblem.badRequest("invalid_secret", "A secret may be at most 10,000 characters.");
        }
        if (!secrets.canSeal()) {
            throw ApiProblem.conflict("master_key_required", "Secrets are stored encrypted; set OPENDBAPI_MASTER_KEY on the server first.");
        }
        return secrets.seal(value);
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ApiProblem.badRequest("invalid_secret",
                    "Name the secret with lower-case letters, digits, '.', '_' and '-', starting with a letter or digit (at most 63).");
        }
    }
}
