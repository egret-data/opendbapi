package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Workspaces and role checks on members (spec §4.0, §8.4, §21.2). */
class WorkspaceMembersIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String slug;
    private String ownerId;
    private ApiClient owner;
    private String authorId;
    private ApiClient author;
    private ApiClient outsider;

    private record Account(String id, ApiClient client) {
    }

    private Account user(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String id = admin.post("/admin/api/users", Map.of("username", name, "password", name + "-password!",
                "systemAdmin", false)).expect(201).json().path("id").asText();
        ApiClient c = client();
        c.login(name, name + "-password!").expect(204);
        return new Account(id, c);
    }

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        Account o = user("owner");
        ownerId = o.id();
        owner = o.client();
        Account a = user("author");
        authorId = a.id();
        author = a.client();
        outsider = user("outsider").client();
        slug = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", slug, "name", "Team " + slug, "ownerUserId", ownerId))
                .expect(201);
        owner.post("/admin/api/ws/" + slug + "/members", Map.of("userId", authorId, "role", "author")).expect(201);
    }

    @Test
    void membersSeeTheWorkspaceInMe() {
        // Act
        var workspaces = author.get("/admin/api/me").expect(200).json().path("workspaces");

        // Assert
        assertThat(workspaces.findValuesAsText("slug")).contains(slug);
        assertThat(workspaces.toString()).contains("\"role\":\"author\"");
    }

    @Test
    void outsidersGet404NotAHintThatTheWorkspaceExists() {
        // Act + Assert
        outsider.get("/admin/api/ws/" + slug + "/members").expectProblem(404, "workspace_not_found");
        outsider.get("/admin/api/ws/no-such-workspace/members").expectProblem(404, "workspace_not_found");
    }

    @Test
    void authorsReadMembersButCannotChangeThem() {
        // Act + Assert
        author.get("/admin/api/ws/" + slug + "/members").expect(200);
        author.patch("/admin/api/ws/" + slug + "/members/" + ownerId, Map.of("role", "viewer"))
                .expectProblem(403, "forbidden");
    }

    @Test
    void onlyOwnersGrantOwner() {
        // Arrange: make the author an admin
        owner.patch("/admin/api/ws/" + slug + "/members/" + authorId, Map.of("role", "admin")).expect(200);

        // Act + Assert: an admin cannot promote themselves to owner, an owner can
        author.patch("/admin/api/ws/" + slug + "/members/" + authorId, Map.of("role", "owner"))
                .expectProblem(403, "forbidden");
        owner.patch("/admin/api/ws/" + slug + "/members/" + authorId, Map.of("role", "owner")).expect(200);
    }

    @Test
    void theLastOwnerCannotLeaveOrStepDown() {
        // Act + Assert
        owner.delete("/admin/api/ws/" + slug + "/members/" + ownerId).expectProblem(409, "last_owner");
        owner.patch("/admin/api/ws/" + slug + "/members/" + ownerId, Map.of("role", "admin"))
                .expectProblem(409, "last_owner");
    }

    @Test
    void ownersRenameButOnlySystemAdminsSetQuotas() {
        // Act + Assert
        owner.patch("/admin/api/workspaces/" + slug, Map.of("name", "Renamed")).expect(200);
        owner.patch("/admin/api/workspaces/" + slug, Map.of("quotas", Map.of("endpoints", 10)))
                .expectProblem(403, "forbidden");
        admin.patch("/admin/api/workspaces/" + slug, Map.of("quotas", Map.of("endpoints", 10))).expect(200);
        assertThat(author.get("/admin/api/workspaces/" + slug).json().path("name").asText()).isEqualTo("Renamed");
    }

    @Test
    void archivingHidesTheWorkspaceFromMembers() {
        // Act
        admin.post("/admin/api/workspaces/" + slug + ":archive", null).expect(200);

        // Assert
        assertThat(author.get("/admin/api/me").json().path("workspaces").findValuesAsText("slug")).doesNotContain(slug);
        author.get("/admin/api/ws/" + slug + "/members").expectProblem(404, "workspace_not_found");
        admin.post("/admin/api/workspaces/" + slug + ":restore", null).expect(200);
        author.get("/admin/api/ws/" + slug + "/members").expect(200);
    }

    @Test
    void duplicateSlugIsAConflict() {
        // Act + Assert
        admin.post("/admin/api/workspaces", Map.of("slug", slug, "name", "Again")).expectProblem(409, "slug_taken");
        admin.post("/admin/api/workspaces", Map.of("slug", "Bad Slug!", "name", "x")).expectProblem(400, "invalid_request");
    }

    @Test
    void memberChangesAreAuditedForTheWorkspace() {
        // Act
        var entries = owner.get("/admin/api/ws/" + slug + "/audit").expect(200).json();

        // Assert
        assertThat(entries.findValuesAsText("action")).contains("workspace.create", "member.add");
    }
}
