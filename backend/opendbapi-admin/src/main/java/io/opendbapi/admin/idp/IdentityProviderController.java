package io.opendbapi.admin.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendbapi.admin.auth.LdapLogin;
import io.opendbapi.admin.auth.OidcRegistrations;
import io.opendbapi.admin.security.CurrentUser;
import io.opendbapi.core.audit.AuditLog;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.identity.GroupRoleMapping;
import io.opendbapi.core.identity.IdentityProvider;
import io.opendbapi.core.identity.IdentityProviderStore;
import io.opendbapi.core.identity.UserAccount;
import io.opendbapi.core.secret.SecretResolver;
import io.opendbapi.core.workspace.WorkspaceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * LDAP and OIDC sign-in providers and their group → role mappings (system admin, spec §8.5).
 * Secrets are write-only: the API reports whether one is set, never its value.
 */
@RestController
@RequestMapping(path = "/admin/api/identity-providers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "identityProviders")
public class IdentityProviderController {

    public enum Type { ldap, oidc }

    private static final Set<String> LDAP_REQUIRED = Set.of("url", "userSearchFilter");
    private static final Set<String> OIDC_REQUIRED = Set.of("issuerUri", "clientId");

    public record ProviderView(
            UUID id,
            Type type,
            String name,
            boolean enabled,
            @Schema(description = "Provider settings without secrets") Map<String, Object> config,
            boolean hasSecret,
            @Schema(description = "env: references are shown; sealed values are not", nullable = true) String secretEnv,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record SaveProvider(
            @NotNull Type type,
            @NotBlank @Size(max = 120) String name,
            boolean enabled,
            @NotNull Map<String, Object> config,
            @Schema(nullable = true, description = "Plain secret (sealed on save) or an env:NAME reference; omit to keep")
            String secret) {
    }

    public record TestRequest(@Schema(nullable = true) String username, @Schema(nullable = true) String password) {
    }

    public record TestResult(boolean ok, List<String> messages) {
    }

    public enum MappingRole { system_admin, owner, admin, script_author, author, viewer }

    public record MappingView(
            @NotBlank @Size(max = 256) String externalGroup,
            @Schema(nullable = true, description = "Workspace slug; null only for system_admin") String workspace,
            @NotNull MappingRole role,
            boolean managed) {
    }

    private final CurrentUser currentUser;
    private final IdentityProviderStore providers;
    private final WorkspaceStore workspaces;
    private final SecretResolver secrets;
    private final LdapLogin ldap;
    private final OidcRegistrations oidc;
    private final AuditLog audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public IdentityProviderController(CurrentUser currentUser, IdentityProviderStore providers, WorkspaceStore workspaces,
                                      SecretResolver secrets, LdapLogin ldap, OidcRegistrations oidc, AuditLog audit,
                                      com.fasterxml.jackson.databind.ObjectMapper json) {
        this.currentUser = currentUser;
        this.providers = providers;
        this.workspaces = workspaces;
        this.secrets = secrets;
        this.ldap = ldap;
        this.oidc = oidc;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping
    @Operation(operationId = "list", summary = "Every sign-in provider")
    public List<ProviderView> list() {
        currentUser.requireSystemAdmin();
        return providers.list().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "get", summary = "One sign-in provider")
    public ProviderView get(@PathVariable UUID id) {
        currentUser.requireSystemAdmin();
        return view(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "create", summary = "Add an LDAP directory or OIDC issuer")
    public ProviderView create(@Valid @RequestBody SaveProvider body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        ObjectNode config = validatedConfig(body);
        IdentityProvider created = providers.create(body.type().name(), body.name(), body.enabled(), config,
                secrets.toReference(body.secret()));
        audit.record(null, caller.id(), "idp.create", "identity_provider", created.id().toString(),
                Map.of("name", created.name(), "type", created.type()));
        return view(created);
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "update", summary = "Change a provider; omit secret to keep it")
    public ProviderView update(@PathVariable UUID id, @Valid @RequestBody SaveProvider body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        IdentityProvider existing = find(id);
        if (!existing.type().equals(body.type().name())) {
            throw ApiProblem.badRequest("type_immutable", "A provider's type cannot change; create a new provider instead.");
        }
        IdentityProvider updated = providers.update(id, body.name(), body.enabled(), validatedConfig(body),
                secrets.toReference(body.secret()));
        audit.record(null, caller.id(), "idp.update", "identity_provider", id.toString(),
                Map.of("name", updated.name(), "enabled", updated.enabled(), "secretChanged", body.secret() != null));
        return view(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "delete", summary = "Remove a provider nobody has signed in through")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UserAccount caller = currentUser.requireSystemAdmin();
        IdentityProvider existing = find(id);
        if (providers.hasUsers(id)) {
            throw ApiProblem.conflict("provider_in_use",
                    "Users have signed in through '%s'. Disable it instead of deleting it.".formatted(existing.name()));
        }
        providers.delete(id);
        audit.record(null, caller.id(), "idp.delete", "identity_provider", id.toString(), Map.of("name", existing.name()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}:test")
    @Operation(operationId = "test", summary = "Check connectivity; for LDAP optionally sign a test user in")
    public TestResult test(@PathVariable UUID id, @RequestBody(required = false) TestRequest body) {
        currentUser.requireSystemAdmin();
        IdentityProvider idp = find(id);
        try {
            if (IdentityProvider.LDAP.equals(idp.type())) {
                return new TestResult(true, ldap.test(idp,
                        body == null ? null : body.username(), body == null ? null : body.password()));
            }
            var registration = oidc.build(idp);
            return new TestResult(true, List.of(
                    "Discovery document loaded from " + registration.getProviderDetails().getIssuerUri() + ".",
                    "Authorization endpoint: " + registration.getProviderDetails().getAuthorizationUri()));
        } catch (ApiProblem e) {
            return new TestResult(false, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return new TestResult(false, List.of("Failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/group-mappings")
    @Operation(operationId = "getMappings", summary = "Directory group → role mappings")
    public List<MappingView> mappings(@PathVariable UUID id) {
        currentUser.requireSystemAdmin();
        find(id);
        return providers.mappings(id).stream().map(this::mappingView).toList();
    }

    @PutMapping("/{id}/group-mappings")
    @Operation(operationId = "replaceMappings", summary = "Replace all mappings; applied at each user's next sign-in")
    public List<MappingView> replaceMappings(@PathVariable UUID id, @Valid @RequestBody List<@Valid MappingView> body) {
        UserAccount caller = currentUser.requireSystemAdmin();
        find(id);
        List<GroupRoleMapping> mappings = body.stream().map(m -> {
            boolean systemAdmin = m.role() == MappingRole.system_admin;
            if (systemAdmin != (m.workspace() == null || m.workspace().isBlank())) {
                throw ApiProblem.badRequest("invalid_mapping",
                        "Group '%s': a workspace is required for workspace roles and not allowed for system_admin."
                                .formatted(m.externalGroup()));
            }
            UUID workspaceId = systemAdmin ? null : workspaces.findBySlug(m.workspace())
                    .orElseThrow(() -> ApiProblem.badRequest("unknown_workspace",
                            "Workspace '%s' does not exist.".formatted(m.workspace())))
                    .id();
            return new GroupRoleMapping(null, id, m.externalGroup().trim(), workspaceId, m.role().name(), m.managed());
        }).toList();
        try {
            List<GroupRoleMapping> saved = providers.replaceMappings(id, mappings);
            audit.record(null, caller.id(), "idp.mappings", "identity_provider", id.toString(), Map.of("count", saved.size()));
            return saved.stream().map(this::mappingView).toList();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiProblem.badRequest("duplicate_mapping", "Each group can map to a workspace only once.");
        }
    }

    private IdentityProvider find(UUID id) {
        return providers.find(id)
                .orElseThrow(() -> ApiProblem.notFound("provider_not_found", "That sign-in provider does not exist."));
    }

    private ObjectNode validatedConfig(SaveProvider body) {
        ObjectNode config = json.valueToTree(body.config());
        Set<String> required = body.type() == Type.ldap ? LDAP_REQUIRED : OIDC_REQUIRED;
        for (String field : required) {
            JsonNode value = config.get(field);
            if (value == null || value.asText().isBlank()) {
                throw ApiProblem.badRequest("invalid_config", "config.%s is required for %s.".formatted(field, body.type()));
            }
        }
        if (body.type() == Type.ldap) {
            String url = config.get("url").asText();
            if (!url.startsWith("ldap://") && !url.startsWith("ldaps://")) {
                throw ApiProblem.badRequest("invalid_config", "config.url must start with ldap:// or ldaps://.");
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private ProviderView view(IdentityProvider p) {
        String ref = p.secretRef();
        return new ProviderView(p.id(), Type.valueOf(p.type()), p.name(), p.enabled(),
                json.convertValue(p.config(), Map.class), ref != null,
                ref != null && ref.startsWith("env:") ? ref : null, p.createdAt(), p.updatedAt());
    }

    private MappingView mappingView(GroupRoleMapping m) {
        String slug = m.workspaceId() == null ? null : workspaces.list().stream()
                .filter(w -> w.id().equals(m.workspaceId())).map(w -> w.slug()).findFirst().orElse(null);
        return new MappingView(m.externalGroup(), slug, MappingRole.valueOf(m.role()), m.managed());
    }
}
