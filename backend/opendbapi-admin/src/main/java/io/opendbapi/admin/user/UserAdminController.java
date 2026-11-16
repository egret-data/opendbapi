package io.opendbapi.admin.user;

import io.opendbapi.admin.security.CurrentUser;
import io.opendbapi.admin.security.Passwords;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User administration (system admin, spec §21.1). LDAP/SSO users appear on first sign-in. */
@RestController
@RequestMapping(path = "/admin/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "users")
public class UserAdminController {

    public record UserView(
            UUID id,
            String username,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true) String email,
            String source,
            boolean systemAdmin,
            boolean disabled,
            boolean locked,
            @Schema(nullable = true) OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt) {

        static UserView of(UserAccount u) {
            return new UserView(u.id(), u.username(), u.displayName(), u.email(), u.source(), u.systemAdmin(),
                    u.disabled(), u.lockedAt(OffsetDateTime.now()), u.lastLoginAt(), u.createdAt());
        }
    }

    public record CreateUser(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._@-]+$",
                    message = "may only contain letters, digits and . _ @ -") String username,
            @Schema(nullable = true) @Size(max = 120) String displayName,
            @Schema(nullable = true) @Email @Size(max = 254) String email,
            @NotBlank String password,
            boolean systemAdmin) {
    }

    public record UpdateUser(
            @Schema(nullable = true) @Size(max = 120) String displayName,
            @Schema(nullable = true) @Email @Size(max = 254) String email,
            @Schema(nullable = true) Boolean systemAdmin,
            @Schema(nullable = true) Boolean disabled) {
    }

    public record ResetPassword(@NotBlank String password) {
    }

    private final CurrentUser currentUser;
    private final UserStore users;
    private final Passwords passwords;
    private final AuditLog audit;

    public UserAdminController(CurrentUser currentUser, UserStore users, Passwords passwords, AuditLog audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "listUsers", summary = "Every user")
    public List<UserView> list() {
        currentUser.requireSystemAdmin();
        return users.list().stream().map(UserView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createUser", summary = "Create a local user")
    public UserView create(@Valid @RequestBody CreateUser body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        UserAccount created = users.createLocal(body.username(), body.displayName(), body.email(),
                passwords.hash(body.password()), body.systemAdmin());
        audit.record(null, caller.id(), "user.create", "user", created.id().toString(),
                Map.of("username", created.username(), "systemAdmin", created.systemAdmin()));
        return UserView.of(created);
    }

    @PatchMapping("/{id}")
    @Transactional
    @Operation(operationId = "updateUser", summary = "Edit, disable or promote a user")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUser body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        UserAccount target = users.findById(id)
                .orElseThrow(() -> ApiProblem.notFound("user_not_found", "That user does not exist."));
        boolean losesAdmin = target.systemAdmin()
                && (Boolean.FALSE.equals(body.systemAdmin()) || Boolean.TRUE.equals(body.disabled()));
        if (losesAdmin && target.id().equals(caller.id())) {
            throw ApiProblem.conflict("self_lockout", "You cannot disable or demote yourself. Ask another system admin.");
        }
        UserAccount updated = users.update(id, body.displayName(), body.email(), body.systemAdmin(), body.disabled());
        if (losesAdmin && !users.anySystemAdmin()) {
            throw ApiProblem.conflict("last_admin", "This would leave no enabled system administrator.");
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("username", updated.username());
        if (body.systemAdmin() != null) {
            detail.put("systemAdmin", body.systemAdmin());
        }
        if (body.disabled() != null) {
            detail.put("disabled", body.disabled());
        }
        audit.record(null, caller.id(), "user.update", "user", id.toString(), detail);
        return UserView.of(updated);
    }

    @PostMapping("/{id}:reset-password")
    @Operation(operationId = "resetUserPassword", summary = "Set a new password for a local user")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPassword body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        UserAccount target = users.findById(id)
                .orElseThrow(() -> ApiProblem.notFound("user_not_found", "That user does not exist."));
        if (!target.local()) {
            throw ApiProblem.badRequest("not_local", "%s signs in through %s; reset the password there."
                    .formatted(target.username(), target.source().toUpperCase()));
        }
        users.setPassword(id, passwords.hash(body.password()));
        audit.record(null, caller.id(), "user.reset_password", "user", id.toString(), Map.of("username", target.username()));
        return ResponseEntity.noContent().build();
    }
}
