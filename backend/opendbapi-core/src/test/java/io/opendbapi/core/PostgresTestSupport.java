package io.opendbapi.core;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL container per test JVM, set up like production: Flyway migrates as the
 * schema owner, the application connects as a separate role subject to row-level security.
 */
public abstract class PostgresTestSupport {

    public static final String APP_ROLE = "opendbapi_app";

    @SuppressWarnings("resource") // lives for the whole JVM, stopped by Ryuk
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("opendbapi")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/init-roles.sql"),
                    "/docker-entrypoint-initdb.d/init-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> "app");
        registry.add("spring.flyway.user", () -> "opendbapi_owner");
        registry.add("spring.flyway.password", () -> "owner");
    }
}
