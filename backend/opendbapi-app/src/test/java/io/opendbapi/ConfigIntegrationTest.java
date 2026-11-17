package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Configuration as code: plan/apply of YAML files and environments (spec §15). */
class ConfigIntegrationTest extends AppTestSupport {

    static final String FILE = """
            apiVersion: opendbapi/v1
            kind: Datasource
            metadata: {name: meta}
            spec:
              dialect: POSTGRESQL
              host: %s
              port: %d
              database: opendbapi
              username: %s
              passwordRef: secret:db
              readOnly: true
            ---
            apiVersion: opendbapi/v1
            kind: Group
            metadata: {name: sales}
            ---
            apiVersion: opendbapi/v1
            kind: ServiceAccount
            metadata: {name: reporter}
            spec: {description: Monthly reports, attributes: {region: eu}}
            ---
            apiVersion: opendbapi/v1
            kind: Endpoint
            metadata: {name: Numbers, group: sales, state: live}
            spec:
              method: GET
              path: numbers
              access: public
              datasource: meta
              task:
                type: sql
                transaction: false
                statements:
                  - id: main
                    template: select n, 'code-' || n as code from generate_series(1, 3) as n
              tests:
                - name: three rows
                  expect: {status: 200, rowCount: {min: 3, max: 3}}
            ---
            apiVersion: opendbapi/v1
            kind: DataPolicy
            spec: {kind: mask, column: code, endpoint: GET /numbers, mask: {type: partial, keepStart: 2, keepEnd: 0}}
            ---
            apiVersion: opendbapi/v1
            kind: Grant
            spec: {serviceAccount: reporter, endpoint: GET /numbers}
            """;

    private ApiClient admin;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
    }

    private String workspace(String prefix) {
        String ws = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", prefix + " " + ws)).expect(201);
        return ws;
    }

    private JsonNode plan(String ws, String yaml, String query) {
        return admin.postYaml("/admin/api/ws/" + ws + "/config:plan?" + query, yaml).expect(200).json();
    }

    private JsonNode apply(String ws, String yaml, String query) {
        return admin.postYaml("/admin/api/ws/" + ws + "/config:apply?" + query, yaml).expect(200).json();
    }

    private Map<String, String> actions(JsonNode plan) {
        Map<String, String> out = new HashMap<>();
        plan.path("changes").forEach(c -> out.put(c.path("kind").asText() + " " + c.path("name").asText(),
                c.path("action").asText() + (c.path("details").isEmpty() ? "" : " " + c.path("details"))));
        return out;
    }

    private JsonNode serve(String ws, String path) {
        return client().get("/api/" + ws + "/" + path).expect(200).json();
    }

    private String devWorkspace() {
        String dev = workspace("dev");
        admin.post("/admin/api/ws/" + dev + "/secrets", Map.of("name", "db", "value", POSTGRES.getPassword())).expect(201);
        JsonNode applied = apply(dev, FILE.formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getUsername()), "publish=true");
        assertThat(applied.path("published").get(0).path("result").asText()).isEqualTo("published");
        return dev;
    }

    private static String without(String yaml, String kind) {
        return Arrays.stream(yaml.split("(?m)^---\\s*$")).filter(d -> !d.contains("kind: " + kind + "\n"))
                .collect(Collectors.joining("---\n"));
    }

    @Test
    void applyingAFileBuildsTheWorkspaceAndItsExportPlansAsUnchanged() {
        // Arrange
        String dev = devWorkspace();

        // Act
        String exported = admin.get("/admin/api/ws/" + dev + "/export").expect(200).body();
        JsonNode again = plan(dev, exported, "publish=true");
        JsonNode served = serve(dev, "numbers");

        // Assert
        assertThat(again.path("counts").path("unchanged").asInt()).isEqualTo(6);
        assertThat(again.path("counts").size()).as(again.toString()).isEqualTo(1);
        assertThat(served.path("data")).hasSize(3);
        assertThat(served.path("data").get(0).path("code").asText()).startsWith("co").doesNotContain("code-1");
        assertThat(exported).contains("passwordRef: secret:db").contains("serviceAccount: reporter");
    }

    @Test
    void anEnvironmentPromotesTheFileToAnotherWorkspaceAndPublishesAfterTests() {
        // Arrange
        String exported = admin.get("/admin/api/ws/" + devWorkspace() + "/export").expect(200).body();
        String prod = workspace("prod");
        admin.post("/admin/api/ws/" + prod + "/datasources", Map.of("name", "prod-db", "dialect", "POSTGRESQL",
                "host", POSTGRES.getHost(), "port", POSTGRES.getMappedPort(5432), "database", "opendbapi",
                "username", POSTGRES.getUsername(), "password", POSTGRES.getPassword(), "properties", Map.of(),
                "readOnly", true, "pool", Map.of())).expect(201);
        admin.put("/admin/api/ws/" + prod + "/environments/prod", Map.of("bindings", Map.of("meta", "prod-db"))).expect(200);

        // Act
        JsonNode planned = plan(prod, exported, "environment=prod&publish=true");
        JsonNode applied = apply(prod, exported, "environment=prod&publish=true&expectedDigest=" + planned.path("digest").asText());
        JsonNode servedV1 = serve(prod, "numbers");
        JsonNode replanned = plan(prod, exported, "environment=prod&publish=true");

        String twoRows = exported.replace("generate_series(1, 3)", "generate_series(1, 2)");
        JsonNode failing = apply(prod, twoRows, "environment=prod&publish=true");
        JsonNode stillV1 = serve(prod, "numbers");
        JsonNode fixed = apply(prod, twoRows.replace("max: 3", "max: 2").replace("min: 3", "min: 2"), "environment=prod&publish=true");
        JsonNode servedV2 = serve(prod, "numbers");

        // Assert
        assertThat(actions(planned)).containsEntry("Datasource meta", "skip [\"bound to datasource 'prod-db' in environment prod\"]")
                .containsEntry("Endpoint GET /numbers", "create [\"publish\"]")
                .containsEntry("Grant reporter → endpoint GET /numbers", "create");
        assertThat(applied.path("published").get(0).path("result").asText()).isEqualTo("published");
        assertThat(servedV1.path("data")).hasSize(3);
        assertThat(replanned.path("counts").has("create")).isFalse();
        assertThat(replanned.path("counts").has("update")).as(replanned.toString()).isFalse();
        assertThat(actions(failing.path("plan")).get("Endpoint GET /numbers")).isEqualTo("update [\"spec.task\",\"publish\"]");
        assertThat(failing.path("published").get(0).path("result").asText()).isEqualTo("tests_failed");
        assertThat(stillV1.path("data")).hasSize(3);
        assertThat(fixed.path("published").get(0).path("result").asText()).isEqualTo("published");
        assertThat(servedV2.path("data")).hasSize(2);
        assertThat(servedV2.path("meta").path("version").asInt()).isEqualTo(2);
    }

    @Test
    void pruneDeletesWhatTheFileNoLongerListsAndNothingElseDoes() {
        // Arrange
        String dev = devWorkspace();
        String exported = admin.get("/admin/api/ws/" + dev + "/export").expect(200).body();
        String noGrant = without(exported, "Grant");

        // Act
        JsonNode withoutPrune = plan(dev, noGrant, "");
        JsonNode pruned = apply(dev, noGrant, "prune=true");
        JsonNode after = plan(dev, noGrant, "prune=true");

        // Assert
        assertThat(withoutPrune.path("counts").has("delete")).isFalse();
        assertThat(actions(pruned.path("plan"))).containsEntry("Grant reporter → endpoint GET /numbers", "delete");
        assertThat(after.path("counts").has("delete")).isFalse();
        assertThat(admin.get("/admin/api/ws/" + dev + "/export").expect(200).body()).doesNotContain("kind: Grant");
    }

    @Test
    void badFilesAndStalePlansAreRefusedWithoutChangingAnything() {
        // Arrange
        String ws = workspace("bad");
        String file = FILE.formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getUsername());

        // Act + Assert
        admin.postYaml("/admin/api/ws/" + ws + "/config:plan", file.replace("opendbapi/v1", "v0")).expectProblem(400, "invalid_config");
        admin.postYaml("/admin/api/ws/" + ws + "/config:plan", file.replace("passwordRef: secret:db", "password: hunter2"))
                .expectProblem(400, "invalid_config");
        admin.postYaml("/admin/api/ws/" + ws + "/config:plan?environment=nope", file).expectProblem(400, "unknown_environment");
        admin.postYaml("/admin/api/ws/" + ws + "/config:apply?expectedDigest=0000", file).expectProblem(409, "plan_changed");
        admin.postYaml("/admin/api/ws/" + ws + "/config:apply", file).expectProblem(400, "secret_unavailable");
        assertThat(admin.get("/admin/api/ws/" + ws + "/export").expect(200).body()).doesNotContain("kind:");
    }

    @Test
    void theTextFormatIsForTerminals() {
        // Arrange
        String dev = devWorkspace();
        String exported = admin.get("/admin/api/ws/" + dev + "/export").expect(200).body();

        // Act
        String unchanged = admin.postYaml("/admin/api/ws/" + dev + "/config:plan?format=text", exported).expect(200).body();
        String changed = admin.postYaml("/admin/api/ws/" + dev + "/config:plan?format=text",
                exported.replace("description: Monthly reports", "description: Weekly reports")).expect(200).body();

        // Assert
        assertThat(unchanged).contains("No changes.").contains("0 to create, 0 to update, 0 to delete, 6 unchanged.");
        assertThat(changed).contains("~ ServiceAccount reporter  (spec.description)");
    }
}
