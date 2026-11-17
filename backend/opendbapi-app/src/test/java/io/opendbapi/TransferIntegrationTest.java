package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DBAPI import (spec §15.3) and YAML export (spec §15.1). */
class TransferIntegrationTest extends AppTestSupport {

    private ApiClient admin;
    private String ws;

    @BeforeEach
    void arrange() {
        admin = signedInAdmin();
        ws = "tr-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Transfer " + ws)).expect(201);
    }

    private String ws(String path) {
        return "/admin/api/ws/" + ws + path;
    }

    /** Shaped like DBAPI's group.json, datasource.json and api_config.json. */
    private static Map<String, Object> dbapiExport() {
        return Map.of(
                "groups", List.of(Map.of("id", "g1", "name", "Reports")),
                "datasources", List.of(
                        Map.of("id", "d1", "name", "crm", "type", "jdbc", "note", "CRM replica",
                                "detail", "{\"url\":\"jdbc:mysql://db.corp.local:3307/crm?useSSL=false\",\"username\":\"reader\",\"password\":\"secret\"}"),
                        Map.of("id", "d2", "name", "search", "type", "elasticsearch", "detail", "{}")),
                "apis", List.of(
                        Map.of("id", "a1", "name", "Users", "path", "/api/users", "access", 0, "groupId", "g1",
                                "contentType", "application/x-www-form-urlencoded", "note", "All users",
                                "params", "[{\"name\":\"id\",\"type\":\"bigint\",\"note\":\"user id\"},{\"name\":\"tags\",\"type\":\"Array<string>\"}]",
                                "task", "[{\"taskType\":1,\"datasourceId\":\"d1\",\"transaction\":0,\"sqlList\":[{\"sqlText\":\"select * from users where id = #{id} order by ${sort}\"}]}]",
                                "paramRules", List.of(Map.of("type", "Groovy", "script", "return true", "message", "x"))),
                        Map.of("id", "a2", "name", "Search", "path", "api/search", "access", 1, "contentType", "application/json",
                                "task", "[{\"taskType\":2,\"datasourceId\":\"d2\"}]"),
                        Map.of("id", "a3", "name", "Bad", "path", "api/bad path!", "task", "[]")));
    }

    @Test
    void dryRunReportsWithoutWritingAndApplyCreatesDrafts() {
        // Act
        JsonNode dry = admin.post(ws("/import/dbapi?dryRun=true"), dbapiExport()).expect(200).json();
        JsonNode endpointsAfterDry = admin.get(ws("/endpoints")).expect(200).json();
        JsonNode applied = admin.post(ws("/import/dbapi?dryRun=false"), dbapiExport()).expect(200).json();
        JsonNode endpointsList = admin.get(ws("/endpoints")).expect(200).json();
        JsonNode datasources = admin.get(ws("/datasources")).expect(200).json();

        // Assert: the dry run and the real run report the same, only the real one writes
        assertThat(dry.path("dryRun").asBoolean()).isTrue();
        assertThat(endpointsAfterDry).isEmpty();
        assertThat(dry.path("created").asInt()).isEqualTo(applied.path("created").asInt()).isEqualTo(3); // group, datasource, endpoint
        assertThat(applied.path("skipped").asInt()).isEqualTo(3); // ES datasource, ES API, bad path

        JsonNode users = endpointsList.get(0);
        assertThat(endpointsList).hasSize(1);
        assertThat(users.path("state").asText()).isEqualTo("draft");
        assertThat(users.path("method").asText()).isEqualTo("GET");
        assertThat(users.path("path").asText()).isEqualTo("api/users");
        JsonNode detail = admin.get(ws("/endpoints/" + users.path("id").asText())).expect(200).json();
        assertThat(detail.path("definition").path("params").findValuesAsText("type")).containsExactly("bigint", "array<string>");
        assertThat(detail.path("definition").path("task").path("statements").get(0).path("template").asText()).contains("#{id}");

        JsonNode crm = datasources.get(0);
        assertThat(crm.path("dialect").asText()).isEqualTo("MYSQL");
        assertThat(crm.path("port").asInt()).isEqualTo(3307);
        assertThat(crm.path("readOnly").asBoolean()).isTrue();
        assertThat(crm.path("hasPassword").asBoolean()).isFalse();

        String notes = applied.path("items").toString();
        assertThat(notes).contains("${").contains("Parameter rules were not imported").contains("Elasticsearch")
                .contains("not a valid OpenDBAPI path");
        // a second import finds what the first created
        JsonNode again = admin.post(ws("/import/dbapi?dryRun=false"), dbapiExport()).expect(200).json();
        assertThat(again.path("items").findValuesAsText("result")).contains("existing").doesNotContain("created");
    }

    @Test
    void exportWritesYamlByNameWithoutSecrets() {
        // Arrange
        admin.post(ws("/import/dbapi?dryRun=false"), dbapiExport()).expect(200);
        String saId = admin.post(ws("/service-accounts"), Map.of("name", "reporting", "disabled", false, "attributes", Map.of("tenant", "acme")))
                .expect(201).json().path("summary").path("id").asText();
        admin.post(ws("/service-accounts/" + saId + "/clients"), Map.of()).expect(201);

        // Act
        ApiClient.Response response = admin.get(ws("/export?format=yaml")).expect(200);
        String yaml = response.body();

        // Assert
        assertThat(yaml).contains("apiVersion: opendbapi/v1").contains("kind: Datasource").contains("kind: Group")
                .contains("kind: Endpoint").contains("kind: ServiceAccount").contains("datasource: crm").contains("group: Reports")
                .contains("tenant: acme").contains("#{id}");
        assertThat(yaml).doesNotContain("secret").doesNotContain("ods_").doesNotContain("odc_").doesNotContain("datasourceId");
        admin.get(ws("/export?format=json")).expectProblem(400, "invalid_format");
    }
}
