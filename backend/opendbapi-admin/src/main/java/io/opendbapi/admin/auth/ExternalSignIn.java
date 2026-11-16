package io.opendbapi.admin.auth;

import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.identity.RoleSync;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared tail of LDAP and OIDC sign-in: provision the user, sync roles, audit (spec §8.5). */
@Service
public class ExternalSignIn {

    /** What the directory said about the person. */
    public record Identity(String externalId, String username, String displayName, String email, Collection<String> groups) {
    }

    private final UserStore users;
    private final RoleSync roleSync;
    private final AuditLog audit;

    public ExternalSignIn(UserStore users, RoleSync roleSync, AuditLog audit) {
        this.users = users;
        this.roleSync = roleSync;
        this.audit = audit;
    }

    @Transactional
    public UserAccount complete(IdentityProvider idp, Identity identity) {
        UserAccount user = users.upsertExternal(idp.type(), idp.id(), identity.externalId(), identity.username(),
                identity.displayName(), identity.email());
        if (user.disabled()) {
            throw LocalLogin.invalid();
        }
        RoleSync.Result sync = roleSync.sync(user, identity.groups());
        users.recordSuccessfulLogin(user.id());
        audit.record(null, user.id(), "auth.login", "user", user.id().toString(), Map.of(
                "source", idp.type(),
                "provider", idp.name(),
                "groups", identity.groups().size(),
                "roleChanges", sync.changes()));
        return users.findById(user.id()).orElseThrow(() -> new ApiProblem(500, "internal", "User vanished"));
    }
}
