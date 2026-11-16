package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Boots the whole standalone app against a production-shaped PostgreSQL. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApplicationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("opendbapi")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/init-roles.sql"),
                    "/docker-entrypoint-initdb.d/init-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("OPENDBAPI_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("OPENDBAPI_DB_PASSWORD", () -> "app");
        registry.add("OPENDBAPI_DB_OWNER_PASSWORD", () -> "owner");
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void healthIsUpWithDatabaseAndMigrations() {
        // Act
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void adminApiAnswersJson() {
        // Act
        ResponseEntity<String> response = http.getForEntity("/admin/api/system/info", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"mode\":\"standalone\"");
    }

    @Test
    void servingAnswersProblemJsonNotTheSpa() {
        // Act
        ResponseEntity<String> response = http.getForEntity("/api/sales/v1/users", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void clientSideRoutesServeTheSpaShell() {
        // Act + Assert
        for (String route : new String[] {"/", "/login", "/w/sales/endpoints/123", "/admin/workspaces"}) {
            ResponseEntity<String> response = http.getForEntity(route, String.class);
            assertThat(response.getStatusCode()).as(route).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(route).contains("spa-shell");
        }
    }

    /**
     * The committed openapi.json is the contract the frontend generates its client from.
     * Regenerate with: mvn -pl opendbapi-app test -Dtest=ApplicationIntegrationTest -Dopenapi.write=true
     */
    @Test
    void committedOpenApiMatchesTheRunningApi() throws Exception {
        // Arrange
        Path committed = Path.of("..", "..", "openapi.json");
        String live = http.getForObject("/admin/api/openapi.json", String.class);
        String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(live)) + "\n";
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(committed, pretty);
        }

        // Act
        String onDisk = Files.exists(committed) ? Files.readString(committed) : "";

        // Assert
        assertThat(onDisk)
                .as("openapi.json is stale; regenerate with -Dopenapi.write=true")
                .isEqualTo(pretty);
    }

    @Test
    void unknownAdminApiIsNotSwallowedBySpa() {
        // Act
        ResponseEntity<String> response = http.getForEntity("/admin/api/does-not-exist", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("spa-shell");
    }
}
