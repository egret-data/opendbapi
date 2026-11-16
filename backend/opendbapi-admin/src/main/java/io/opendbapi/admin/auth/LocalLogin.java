package io.opendbapi.admin.auth;

import io.opendbapi.admin.security.Passwords;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * Username + password against local accounts, with lockout (spec §8.5). Every failure looks
 * the same to the caller — unknown user, wrong password, disabled account — except a lockout,
 * which says how long to wait.
 */
@Service
public class LocalLogin {

    private final UserStore users;
    private final Passwords passwords;
    private final OpenDbApiProperties.Auth settings;

    public LocalLogin(UserStore users, Passwords passwords, OpenDbApiProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.settings = properties.auth();
    }

    public UserAccount authenticate(String username, String password) {
        UserAccount user = users.findLocal(username).orElse(null);
        if (user == null) {
            passwords.spendDecoyCheck(password);
            throw invalid();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (user.lockedAt(now)) {
            throw locked(Duration.between(now, user.lockedUntil()));
        }
        // Local sign-in can be turned off for everyone but system admins (break-glass).
        boolean allowed = !user.disabled() && (settings.localEnabled() || user.systemAdmin());
        if (!passwords.matches(password, user.passwordHash()) || !allowed) {
            if (!user.disabled()) {
                UserAccount after = users.recordFailedLogin(user.id(), settings.lockoutAttempts(), settings.lockoutDuration());
                if (after.lockedAt(now)) {
                    throw locked(settings.lockoutDuration());
                }
            }
            throw invalid();
        }
        return user;
    }

    static ApiProblem invalid() {
        return new ApiProblem(401, "invalid_credentials", "Invalid username or password.");
    }

    private static ApiProblem locked(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return new ApiProblem(429, "account_locked",
                "Too many failed sign-in attempts. Try again in %d minute%s.".formatted(minutes, minutes == 1 ? "" : "s"));
    }
}
