package io.opendbapi;

import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The whole app on a random port against production-shaped PostgreSQL (separate owner and
 * application roles) and the dev OpenLDAP directory. Containers live for the test JVM.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class AppTestSupport {

    public static final String ADMIN_PASSWORD = "bootstrap-admin-pass";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("opendbapi")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/init-roles.sql"),
                    "/docker-entrypoint-initdb.d/init-roles.sql");

    /** Seeded from deploy/dev: alice (opendbapi-admins), bob (sales-authors); password = username. */
    @SuppressWarnings("resource")
    static final GenericContainer<?> LDAP = new GenericContainer<>("osixia/openldap:1.5.0")
            .withCommand("--copy-service")
            .withEnv("LDAP_ORGANISATION", "OpenDBAPI Test")
            .withEnv("LDAP_DOMAIN", "opendbapi.local")
            .withEnv("LDAP_ADMIN_PASSWORD", "admin")
            .withEnv("LDAP_TLS", "false")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../deploy/dev/ldap/50-seed.ldif"),
                    "/container/service/slapd/assets/config/bootstrap/ldif/custom/50-seed.ldif")
            .withExposedPorts(389)
            .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        LDAP.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("OPENDBAPI_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("OPENDBAPI_DB_PASSWORD", () -> "app");
        registry.add("OPENDBAPI_DB_OWNER_PASSWORD", () -> "owner");
        registry.add("opendbapi.auth.bootstrap-admin-password", () -> ADMIN_PASSWORD);
        registry.add("opendbapi.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("TEST_LDAP_BIND_PASSWORD", () -> "admin");
    }

    @LocalServerPort
    protected int port;

    protected ApiClient client() {
        return new ApiClient("http://localhost:" + port);
    }

    protected ApiClient signedInAdmin() {
        ApiClient admin = client();
        admin.login("admin", ADMIN_PASSWORD).expect(204);
        return admin;
    }

    protected static String ldapUrl() {
        return "ldap://" + LDAP.getHost() + ":" + LDAP.getMappedPort(389);
    }
}
