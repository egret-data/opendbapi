package io.opendbapi.admin.auth;

import io.opendbapi.admin.security.CurrentUser;
import io.opendbapi.admin.security.Passwords;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.identity.UserStore;
import io.opendbapi.core.workspace.MembershipStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/admin/api/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "me")
public class MeController {

    public record MyWorkspace(UUID id, String slug, String name, String role) {
    }

    public record Me(
            UUID id,
            String username,
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true) String email,
            String source,
            boolean systemAdmin,
            List<MyWorkspace> workspaces) {
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    private final CurrentUser currentUser;
    private final MembershipStore memberships;
    private final UserStore users;
    private final Passwords passwords;
    private final AuditLog audit;

    public MeController(CurrentUser currentUser, MembershipStore memberships, UserStore users, Passwords passwords,
                        AuditLog audit) {
        this.currentUser = currentUser;
        this.memberships = memberships;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "get", summary = "The signed-in user and their workspaces")
    public Me me() {
        UserAccount user = currentUser.require();
        return new Me(user.id(), user.username(), user.displayName(), user.email(), user.source(), user.systemAdmin(),
                memberships.activeWorkspacesOf(user.id()).stream()
                        .map(w -> new MyWorkspace(w.id(), w.slug(), w.name(), w.role().dbValue()))
                        .toList());
    }

    @PostMapping("/password")
    @Operation(operationId = "changePassword", summary = "Change your own local password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePassword body) {
        UserAccount user = currentUser.require();
        if (!user.local()) {
            throw ApiProblem.badRequest("not_local",
                    "Your password is managed by your directory or SSO provider, not by OpenDBAPI.");
        }
        if (!passwords.matches(body.currentPassword(), user.passwordHash())) {
            throw ApiProblem.badRequest("wrong_password", "The current password is not correct.");
        }
        users.setPassword(user.id(), passwords.hash(body.newPassword()));
        audit.record(null, user.id(), "user.password_changed", "user", user.id().toString(), Map.of());
        return ResponseEntity.noContent().build();
    }
}
