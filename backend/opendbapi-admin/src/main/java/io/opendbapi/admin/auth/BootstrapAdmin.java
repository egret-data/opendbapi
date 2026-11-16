package io.opendbapi.admin.auth;

import io.opendbapi.admin.security.Passwords;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.identity.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First start: create a local system admin named {@code admin} (spec §8.4). The password
 * comes from {@code opendbapi.auth.bootstrap-admin-password}, or is generated and printed
 * once to the log. Nothing happens while any enabled system admin exists.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserStore users;
    private final Passwords passwords;
    private final OpenDbApiProperties properties;

    public BootstrapAdmin(UserStore users, Passwords passwords, OpenDbApiProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.anySystemAdmin()) {
            return;
        }
        String configured = properties.auth().bootstrapAdminPassword();
        String password = configured.isBlank() ? passwords.generate(20) : configured;
        if (users.findLocal("admin").isPresent()) {
            // Never silently re-enable or promote an existing account.
            log.error("No enabled system administrator exists, and a local user 'admin' already exists. "
                    + "Re-enable or promote it in the database to regain access.");
            return;
        }
        users.createLocal("admin", "Administrator", null, passwords.hash(password), true);
        if (configured.isBlank()) {
            log.warn("""

                    ================================================================
                    Created the first system administrator.
                      username: admin
                      password: {}
                    Sign in and change it. This password is not shown again.
                    ================================================================""", password);
        } else {
            log.info("Created the first system administrator 'admin' with the configured bootstrap password.");
        }
    }
}
