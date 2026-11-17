package io.opendbapi.admin.security;

import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.ApiTokenStore;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.workspace.MembershipStore;
import io.opendbapi.core.workspace.Workspace;
import io.opendbapi.core.workspace.WorkspaceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's API tokens (spec §8.6). Only a browser session manages tokens: a token
 * cannot mint or revoke tokens.
 */
@RestController
@RequestMapping("/admin/api/me/tokens")
@Tag(name = "tokens")
public class ApiTokenController {

    public record CreateApiToken(String name,
                                 @Schema(nullable = true, description = "Limit the token to this workspace (slug)") String workspace,
                                 @Schema(nullable = true, description = "Days until it expires, 1–365; null never expires") Integer expiresInDays) {
    }

    public record CreatedApiToken(ApiTokenStore.ApiToken token, @Schema(description = "Shown once; store it now") String secret) {
    }

    private final CurrentUser currentUser;
    private final ApiTokenStore tokens;
    private final WorkspaceStore workspaces;
    private final MembershipStore memberships;
    private final AuditLog audit;

    public ApiTokenController(CurrentUser currentUser, ApiTokenStore tokens, WorkspaceStore workspaces, MembershipStore memberships,
                              AuditLog audit) {
        this.currentUser = currentUser;
        this.tokens = tokens;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.audit = audit;
    }

    private UserAccount sessionUser() {
        if (TokenScope.current() != null) {
            throw ApiProblem.forbidden("API tokens are managed from a signed-in browser session, not with a token.");
        }
        return currentUser.require();
    }

    @GetMapping
    @Operation(operationId = "listApiTokens", summary = "Your API tokens (never their secret)")
    public List<ApiTokenStore.ApiToken> list() {
        return tokens.list(sessionUser().id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createApiToken", summary = "Create an API token for automation; the secret is shown once")
    public CreatedApiToken create(@RequestBody CreateApiToken body) {
        UserAccount user = sessionUser();
        UUID workspaceId = null;
        if (body.workspace() != null && !body.workspace().isBlank()) {
            Workspace ws = workspaces.findBySlug(body.workspace().strip()).filter(Workspace::active)
                    .orElseThrow(() -> ApiProblem.badRequest("invalid_token", "No workspace '%s'.".formatted(body.workspace())));
            if (!user.systemAdmin() && memberships.roleOf(ws.id(), user.id()).isEmpty()) {
                throw ApiProblem.badRequest("invalid_token", "You are not a member of workspace '%s'.".formatted(body.workspace()));
            }
            workspaceId = ws.id();
        }
        Integer days = body.expiresInDays();
        if (days != null && (days < 1 || days > 365)) {
            throw ApiProblem.badRequest("invalid_token", "A token expires in 1 to 365 days, or never.");
        }
        Instant expires = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        String secret = tokens.create(user.id(), body.name(), workspaceId, expires);
        ApiTokenStore.ApiToken created = tokens.list(user.id()).get(0);
        audit.record(workspaceId, user.id(), "api_token.create", "api_token", created.id().toString(),
                Map.of("name", created.name(), "workspace", String.valueOf(created.workspace()), "expiresAt", String.valueOf(expires)));
        return new CreatedApiToken(created, secret);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeApiToken", summary = "Revoke one of your API tokens")
    public void revoke(@PathVariable UUID id) {
        UserAccount user = sessionUser();
        if (!tokens.revoke(user.id(), id)) {
            throw ApiProblem.notFound("token_not_found", "No such token.");
        }
        audit.record(null, user.id(), "api_token.revoke", "api_token", id.toString(), Map.of());
    }
}
