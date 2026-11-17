package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A workspace managed by configuration files only (spec §15.2). */
class ConfigLockIntegrationTest extends AppTestSupport {

    @Test
    void aLockedWorkspaceChangesThroughApplyOnly() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String ws = "lk-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/admin/api/ws/" + ws;
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Locked " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post(base + "/datasources", ds).expect(201).json().path("id").asText();
        String ep = admin.post(base + "/endpoints", Map.of("name", "e", "method", "GET", "path", "e", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        Map<String, Object> edit = Map.of("name", "e2", "method", "GET", "path", "e", "access", "public",
                "definition", Map.of("params", List.of(), "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                        "statements", List.of(Map.of("id", "main", "template", "select 1")))));

        // Act
        var locked = admin.patch("/admin/api/workspaces/" + ws, Map.of("configLocked", true)).expect(200).json();
        var me = admin.get("/admin/api/me").expect(200).body();
        var editEndpoint = admin.patch(base + "/endpoints/" + ep, edit);
        var newGroup = admin.post(base + "/groups", Map.of("name", "g"));
        var newPolicy = admin.post(base + "/data-policies", Map.of("kind", "mask", "endpointId", ep, "column", "x", "mask", Map.of("type", "hash")));
        var validate = admin.post(base + "/endpoints/" + ep + ":validate", Map.of());
        var datasource = admin.post(base + "/datasources", new HashMap<>(Map.of("name", "other", "dialect", "POSTGRESQL",
                "host", POSTGRES.getHost(), "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", "x",
                "password", "y", "properties", Map.of(), "readOnly", true, "pool", Map.of())));
        var applied = admin.postYaml(base + "/config:apply", "apiVersion: opendbapi/v1\nkind: Group\nmetadata: {name: from-git}\n");
        admin.patch("/admin/api/workspaces/" + ws, Map.of("configLocked", false)).expect(200);
        var afterUnlock = admin.patch(base + "/endpoints/" + ep, edit);

        // Assert
        assertThat(locked.path("configLocked").asBoolean()).isTrue();
        assertThat(me).contains("\"slug\":\"" + ws + "\"").contains("\"configLocked\":true");
        editEndpoint.expectProblem(409, "config_locked");
        newGroup.expectProblem(409, "config_locked");
        newPolicy.expectProblem(409, "config_locked");
        validate.expect(200);
        datasource.expect(201);
        applied.expect(200);
        assertThat(admin.get(base + "/groups").expect(200).body()).contains("from-git");
        afterUnlock.expect(200);
    }
}
