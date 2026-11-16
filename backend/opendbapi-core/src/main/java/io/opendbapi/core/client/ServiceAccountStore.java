package io.opendbapi.core.client;

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

/** Service accounts, their OAuth2 clients and endpoint grants (spec §4.4). Row-level secured. */
@Repository
public class ServiceAccountStore {

    public record ServiceAccount(UUID id, String name, @Schema(nullable = true) String description, boolean disabled, Instant createdAt,
                                 int clientCount, int grantCount, @Schema(nullable = true) Instant lastUsedAt) {
    }

    public record Client(UUID id, String clientId, Instant createdAt, @Schema(nullable = true) Instant expiresAt, @Schema(nullable = true) Instant lastUsedAt,
                         @Schema(nullable = true) Instant revokedAt) {
    }

    public record Grant(String resourceType, UUID resourceId, String resourceName,
                        @Schema(nullable = true, description = "Method and path, for an endpoint") String detail, Instant grantedAt) {
    }

    public record EndpointAccess(UUID serviceAccountId, String name, boolean disabled,
                                 @Schema(allowableValues = {"endpoint", "group"}) String via) {
    }

    /** What the token endpoint needs to know about a client. */
    public record ClientLogin(UUID serviceAccountId, String clientId, String secretHash, boolean serviceAccountDisabled,
                              Instant expiresAt, Instant revokedAt) {
        public boolean usable(Instant now) {
            return !serviceAccountDisabled && revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }

    private final JdbcClient jdbc;

    public ServiceAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SUMMARY = """
            select sa.id, sa.name, sa.description, sa.disabled, sa.created_at,
                   (select count(*) from oauth_client c where c.service_account_id = sa.id and c.revoked_at is null) as clients,
                   (select count(*) from endpoint_grant g where g.service_account_id = sa.id) as grants,
                   (select max(c.last_used_at) from oauth_client c where c.service_account_id = sa.id) as last_used
            from service_account sa""";

    public List<ServiceAccount> list() {
        return jdbc.sql(SUMMARY + " order by lower(sa.name)").query(ServiceAccountStore::account).list();
    }

    public Optional<ServiceAccount> find(UUID id) {
        return jdbc.sql(SUMMARY + " where sa.id = ?").param(id).query(ServiceAccountStore::account).optional();
    }

    public UUID create(UUID workspaceId, String name, String description, UUID userId) {
        try {
            return jdbc.sql("insert into service_account (workspace_id, name, description, created_by) values (?, ?, ?, ?) returning id")
                    .params(workspaceId, name, description, userId).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    public boolean update(UUID id, String name, String description, boolean disabled) {
        try {
            return jdbc.sql("update service_account set name = ?, description = ?, disabled = ? where id = ?")
                    .params(name, description, disabled, id).update() > 0;
        } catch (DuplicateKeyException e) {
            throw nameTaken(name);
        }
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from service_account where id = ?").param(id).update() > 0;
    }

    public List<Client> clients(UUID serviceAccountId) {
        return jdbc.sql("""
                select id, client_id, created_at, expires_at, last_used_at, revoked_at from oauth_client
                where service_account_id = ? order by revoked_at nulls first, created_at desc""")
                .param(serviceAccountId)
                .query((rs, i) -> new Client(rs.getObject("id", UUID.class), rs.getString("client_id"), ts(rs, "created_at"),
                        ts(rs, "expires_at"), ts(rs, "last_used_at"), ts(rs, "revoked_at")))
                .list();
    }

    public Client createClient(UUID workspaceId, UUID serviceAccountId, String clientId, String secretHash, Instant expiresAt) {
        UUID id = jdbc.sql("""
                insert into oauth_client (workspace_id, service_account_id, client_id, secret_hash, expires_at)
                values (?, ?, ?, ?, ?) returning id""")
                .params(workspaceId, serviceAccountId, clientId, secretHash, expiresAt == null ? null : Timestamp.from(expiresAt))
                .query(UUID.class).single();
        jdbc.sql("insert into oauth_client_directory (client_id, workspace_id) values (?, ?)").params(clientId, workspaceId).update();
        return clients(serviceAccountId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Returns the client id of the revoked client, if it existed and was active. */
    public Optional<String> revokeClient(UUID serviceAccountId, UUID id) {
        return jdbc.sql("""
                update oauth_client set revoked_at = now()
                where id = ? and service_account_id = ? and revoked_at is null returning client_id""")
                .params(id, serviceAccountId).query(String.class).optional();
    }

    public Optional<ClientLogin> login(String clientId) {
        return jdbc.sql("""
                select c.service_account_id, c.client_id, c.secret_hash, sa.disabled, c.expires_at, c.revoked_at
                from oauth_client c join service_account sa on sa.id = c.service_account_id where c.client_id = ?""")
                .param(clientId)
                .query((rs, i) -> new ClientLogin(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        ts(rs, "expires_at"), ts(rs, "revoked_at")))
                .optional();
    }

    public void touch(String clientId) {
        jdbc.sql("update oauth_client set last_used_at = now() where client_id = ?").param(clientId).update();
    }

    /** Workspace of a client id; readable without a workspace scope (spec §8.1). */
    public Optional<UUID> workspaceOfClient(String clientId) {
        return jdbc.sql("select workspace_id from oauth_client_directory where client_id = ?").param(clientId)
                .query(UUID.class).optional();
    }

    public List<Grant> grants(UUID serviceAccountId) {
        return jdbc.sql("""
                select g.resource_type, g.resource_id, g.granted_at,
                       coalesce(e.name, ag.name) as name,
                       case when e.id is not null then e.method || ' /' || e.path end as detail
                from endpoint_grant g
                left join endpoint e on g.resource_type = 'endpoint' and e.id = g.resource_id
                left join api_group ag on g.resource_type = 'group' and ag.id = g.resource_id
                where g.service_account_id = ? order by g.resource_type desc, lower(coalesce(e.name, ag.name))""")
                .param(serviceAccountId)
                .query((rs, i) -> new Grant(rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getString("name"), rs.getString("detail"), ts(rs, "granted_at")))
                .list();
    }

    public void grant(UUID workspaceId, UUID serviceAccountId, String resourceType, UUID resourceId, UUID userId) {
        String table = switch (resourceType) {
            case "endpoint" -> "endpoint";
            case "group" -> "api_group";
            default -> throw ApiProblem.badRequest("invalid_resource_type", "resourceType must be endpoint or group.");
        };
        boolean exists = jdbc.sql("select exists (select 1 from " + table + " where id = ?)").param(resourceId)
                .query(Boolean.class).single();
        if (!exists) {
            throw ApiProblem.notFound("resource_not_found", "That %s does not exist.".formatted(resourceType));
        }
        jdbc.sql("""
                insert into endpoint_grant (workspace_id, service_account_id, resource_type, resource_id, granted_by)
                values (?, ?, ?, ?, ?) on conflict do nothing""")
                .params(workspaceId, serviceAccountId, resourceType, resourceId, userId).update();
    }

    public boolean revokeGrant(UUID serviceAccountId, String resourceType, UUID resourceId) {
        return jdbc.sql("delete from endpoint_grant where service_account_id = ? and resource_type = ? and resource_id = ?")
                .params(serviceAccountId, resourceType, resourceId).update() > 0;
    }

    /** Service accounts that may call an endpoint, directly or through its group. */
    public List<EndpointAccess> endpointAccess(UUID endpointId) {
        return jdbc.sql("""
                select sa.id, sa.name, sa.disabled, min(g.resource_type) as via
                from endpoint_grant g join service_account sa on sa.id = g.service_account_id
                where (g.resource_type = 'endpoint' and g.resource_id = ?)
                   or (g.resource_type = 'group' and g.resource_id = (select group_id from endpoint where id = ?))
                group by sa.id, sa.name, sa.disabled order by lower(sa.name)""")
                .params(endpointId, endpointId)
                .query((rs, i) -> new EndpointAccess(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
                .list();
    }

    /** Whether a caller may call an endpoint right now (spec §8.1 steps 5–6). */
    public CallerStatus callerStatus(String clientId, UUID endpointId) {
        return jdbc.sql("""
                select sa.disabled, c.revoked_at is not null or (c.expires_at is not null and c.expires_at <= now()) as dead,
                       exists (select 1 from endpoint_grant g where g.service_account_id = sa.id and (
                           (g.resource_type = 'endpoint' and g.resource_id = ?)
                        or (g.resource_type = 'group' and g.resource_id = (select group_id from endpoint where id = ?)))) as granted
                from oauth_client c join service_account sa on sa.id = c.service_account_id where c.client_id = ?""")
                .params(endpointId, endpointId, clientId)
                .query((rs, i) -> rs.getBoolean("disabled") || rs.getBoolean("dead") ? CallerStatus.INVALID
                        : rs.getBoolean("granted") ? CallerStatus.GRANTED : CallerStatus.NOT_GRANTED)
                .optional().orElse(CallerStatus.INVALID);
    }

    public enum CallerStatus { GRANTED, NOT_GRANTED, INVALID }

    private static ServiceAccount account(ResultSet rs, int i) throws SQLException {
        return new ServiceAccount(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("disabled"), ts(rs, "created_at"), rs.getInt("clients"), rs.getInt("grants"), ts(rs, "last_used"));
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ApiProblem nameTaken(String name) {
        return ApiProblem.conflict("name_taken", "A service account named '%s' already exists.".formatted(name));
    }
}
