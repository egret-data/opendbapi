package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Major versions and deprecation travel in configuration files (spec §15.1, §16). */
class ConfigLineageIntegrationTest extends AppTestSupport {

    @Test
    void lineageAndDeprecationRoundTripAndPromote() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String dev = "ld-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", dev, "name", "Dev " + dev)).expect(201);
        admin.post("/admin/api/ws/" + dev + "/secrets", Map.of("name", "db", "value", POSTGRES.getPassword())).expect(201);
        String file = ConfigIntegrationTest.FILE.formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getUsername());
        admin.postYaml("/admin/api/ws/" + dev + "/config:apply?publish=true", file).expect(200);
        String v1 = admin.get("/admin/api/ws/" + dev + "/endpoints").json().get(0).path("id").asText();
        String v2 = admin.post("/admin/api/ws/" + dev + "/endpoints/" + v1 + ":new-major", Map.of()).expect(201).json()
                .path("summary").path("id").asText();
        admin.post("/admin/api/ws/" + dev + "/endpoints/" + v1 + ":deprecate", Map.of("sunset", "2027-06-30T00:00:00Z")).expect(200);

        String prod = "lp-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", prod, "name", "Prod " + prod)).expect(201);
        admin.post("/admin/api/ws/" + prod + "/datasources", new HashMap<>(Map.of("name", "prod-db", "dialect", "POSTGRESQL",
                "host", POSTGRES.getHost(), "port", POSTGRES.getMappedPort(5432), "database", "opendbapi",
                "username", POSTGRES.getUsername(), "password", POSTGRES.getPassword(), "properties", Map.of(),
                "readOnly", true, "pool", Map.of()))).expect(201);
        admin.put("/admin/api/ws/" + prod + "/environments/prod", Map.of("bindings", Map.of("meta", "prod-db"))).expect(200);

        // Act
        String exported = admin.get("/admin/api/ws/" + dev + "/export").expect(200).body();
        JsonNode replan = admin.postYaml("/admin/api/ws/" + dev + "/config:plan", exported).expect(200).json();
        admin.postYaml("/admin/api/ws/" + prod + "/config:apply?environment=prod", exported).expect(200);
        JsonNode prodEndpoints = admin.get("/admin/api/ws/" + prod + "/endpoints").expect(200).json();
        JsonNode prodV2 = null;
        for (JsonNode e : prodEndpoints) {
            if (e.path("path").asText().equals("v2/numbers")) {
                prodV2 = e;
            }
        }
        JsonNode lineage = admin.get("/admin/api/ws/" + prod + "/endpoints/" + prodV2.path("id").asText() + "/lineage").expect(200).json();
        JsonNode prodReplan = admin.postYaml("/admin/api/ws/" + prod + "/config:plan?environment=prod", exported).expect(200).json();

        // Assert
        assertThat(v2).isNotBlank();
        assertThat(exported).contains("majorOf: GET /numbers").contains("major: 2").contains("deprecated: true")
                .contains("sunset: 2027-06-30T00:00:00Z");
        assertThat(replan.path("counts").has("update")).as(replan.toString()).isFalse();
        assertThat(prodV2.path("major").asInt()).isEqualTo(2);
        assertThat(lineage).hasSize(2);
        assertThat(lineage.get(0).path("path").asText()).isEqualTo("numbers");
        assertThat(lineage.get(0).path("deprecatedAt").isNull()).isFalse();
        assertThat(lineage.get(0).path("sunsetAt").asText()).startsWith("2027-06-30");
        assertThat(prodReplan.path("counts").has("update")).as(prodReplan.toString()).isFalse();
    }
}
