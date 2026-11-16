package io.opendbapi.core.identity;

import io.opendbapi.core.error.ApiProblem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserStore {

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("select * from users where id = ?").param(id).query(UserStore::map).optional();
    }

    public Optional<UserAccount> findLocal(String username) {
        return jdbc.sql("select * from users where source = 'local' and lower(username) = lower(?)")
                .param(username)
                .query(UserStore::map)
                .optional();
    }

    public boolean anySystemAdmin() {
        return jdbc.sql("select exists(select 1 from users where system_admin and not disabled)")
                .query(Boolean.class)
                .single();
    }

    public UserAccount createLocal(String username, String displayName, String email, String passwordHash, boolean systemAdmin) {
        try {
            return jdbc.sql("""
                    insert into users (username, display_name, email, source, password_hash, system_admin)
                    values (?, ?, ?, 'local', ?, ?) returning *""")
                    .params(username, displayName, email, passwordHash, systemAdmin)
                    .query(UserStore::map)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ApiProblem.conflict("username_taken", "A local user named '%s' already exists.".formatted(username));
        }
    }

    /**
     * Creates the user on first sign-in through an identity provider, or refreshes the
     * profile fields the provider owns on later sign-ins (spec §8.5).
     */
    public UserAccount upsertExternal(String source, UUID idpId, String externalId, String username,
                                      String displayName, String email) {
        return jdbc.sql("""
                insert into users (username, display_name, email, source, idp_id, external_id)
                values (?, ?, ?, ?, ?, ?)
                on conflict (idp_id, external_id) where source <> 'local'
                do update set username = excluded.username,
                              display_name = excluded.display_name,
                              email = excluded.email
                returning *""")
                .params(username, displayName, email, source, idpId, externalId)
                .query(UserStore::map)
                .single();
    }

    /** Counts a failed local sign-in; locks the account once {@code maxAttempts} is reached. */
    public UserAccount recordFailedLogin(UUID id, int maxAttempts, Duration lockFor) {
        return jdbc.sql("""
                update users set
                  failed_logins = case when failed_logins + 1 >= ? then 0 else failed_logins + 1 end,
                  locked_until  = case when failed_logins + 1 >= ? then now() + make_interval(secs => ?) else locked_until end
                where id = ? returning *""")
                .params(maxAttempts, maxAttempts, lockFor.toSeconds(), id)
                .query(UserStore::map)
                .single();
    }

    public void recordSuccessfulLogin(UUID id) {
        jdbc.sql("update users set failed_logins = 0, locked_until = null, last_login_at = now() where id = ?")
                .param(id)
                .update();
    }

    public void setPassword(UUID id, String passwordHash) {
        jdbc.sql("update users set password_hash = ?, failed_logins = 0, locked_until = null where id = ? and source = 'local'")
                .params(passwordHash, id)
                .update();
    }

    public UserAccount update(UUID id, String displayName, String email, Boolean systemAdmin, Boolean disabled) {
        return jdbc.sql("""
                update users set display_name = coalesce(?, display_name),
                                 email = coalesce(?, email),
                                 system_admin = coalesce(?, system_admin),
                                 disabled = coalesce(?, disabled)
                where id = ? returning *""")
                .params(displayName, email, systemAdmin, disabled, id)
                .query(UserStore::map)
                .single();
    }

    public void setSystemAdmin(UUID id, boolean systemAdmin) {
        jdbc.sql("update users set system_admin = ? where id = ?").params(systemAdmin, id).update();
    }

    public List<UserAccount> list() {
        return jdbc.sql("select * from users order by lower(coalesce(display_name, username))")
                .query(UserStore::map)
                .list();
    }

    /** Case-insensitive match on username, display name or email, for member pickers. */
    public List<UserAccount> search(String query, int limit) {
        String like = "%" + query.toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                select * from users
                where not disabled
                  and (lower(username) like ? or lower(coalesce(display_name, '')) like ? or lower(coalesce(email, '')) like ?)
                order by lower(username) limit ?""")
                .params(like, like, like, limit)
                .query(UserStore::map)
                .list();
    }

    static UserAccount map(ResultSet rs, int row) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("source"),
                rs.getObject("idp_id", UUID.class),
                rs.getString("external_id"),
                rs.getString("password_hash"),
                rs.getBoolean("system_admin"),
                rs.getBoolean("disabled"),
                rs.getInt("failed_logins"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getObject("last_login_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
