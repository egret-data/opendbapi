package io.opendbapi.core.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdentityProviderStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IdentityProviderStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<IdentityProvider> list() {
        return jdbc.sql("select * from identity_provider order by type, lower(name)").query(this::map).list();
    }

    public List<IdentityProvider> listEnabled(String type) {
        return jdbc.sql("select * from identity_provider where enabled and type = ? order by lower(name)")
                .param(type)
                .query(this::map)
                .list();
    }

    public Optional<IdentityProvider> find(UUID id) {
        return jdbc.sql("select * from identity_provider where id = ?").param(id).query(this::map).optional();
    }

    public IdentityProvider create(String type, String name, boolean enabled, JsonNode config, String secretRef) {
        return jdbc.sql("""
                insert into identity_provider (type, name, enabled, config, secret_ref)
                values (?, ?, ?, ?::jsonb, ?) returning *""")
                .params(type, name, enabled, write(config), secretRef)
                .query(this::map)
                .single();
    }

    /** {@code secretRef == null} keeps the stored secret. */
    public IdentityProvider update(UUID id, String name, boolean enabled, JsonNode config, String secretRef) {
        return jdbc.sql("""
                update identity_provider set name = ?, enabled = ?, config = ?::jsonb,
                       secret_ref = coalesce(?, secret_ref), updated_at = now()
                where id = ? returning *""")
                .params(name, enabled, write(config), secretRef, id)
                .query(this::map)
                .single();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from identity_provider where id = ?").param(id).update() > 0;
    }

    public boolean hasUsers(UUID id) {
        return jdbc.sql("select exists(select 1 from users where idp_id = ?)").param(id).query(Boolean.class).single();
    }

    public List<GroupRoleMapping> mappings(UUID idpId) {
        return jdbc.sql("select * from group_role_mapping where idp_id = ? order by external_group")
                .param(idpId)
                .query(IdentityProviderStore::mapMapping)
                .list();
    }

    @Transactional
    public List<GroupRoleMapping> replaceMappings(UUID idpId, List<GroupRoleMapping> mappings) {
        jdbc.sql("delete from group_role_mapping where idp_id = ?").param(idpId).update();
        for (GroupRoleMapping m : mappings) {
            jdbc.sql("""
                    insert into group_role_mapping (idp_id, external_group, workspace_id, role, managed)
                    values (?, ?, ?, ?, ?)""")
                    .params(idpId, m.externalGroup(), m.workspaceId(), m.role(), m.managed())
                    .update();
        }
        return mappings(idpId);
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private IdentityProvider map(ResultSet rs, int row) throws SQLException {
        try {
            return new IdentityProvider(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    rs.getString("name"),
                    rs.getBoolean("enabled"),
                    json.readTree(rs.getString("config")),
                    rs.getString("secret_ref"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new SQLException("Bad identity provider config", e);
        }
    }

    private static GroupRoleMapping mapMapping(ResultSet rs, int row) throws SQLException {
        return new GroupRoleMapping(
                rs.getObject("id", UUID.class),
                rs.getObject("idp_id", UUID.class),
                rs.getString("external_group"),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("role"),
                rs.getBoolean("managed"));
    }
}
