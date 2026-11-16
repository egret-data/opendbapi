package io.opendbapi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.PostgresTestSupport;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;

/** A read-only datasource must refuse writes even with autocommit on, per dialect (spec §4.6). */
@SpringBootTest
@TestPropertySource(properties = "RO_TEST_PASSWORD=test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadOnlyEnforcementTest extends PostgresTestSupport {

    @Autowired DataSourceRegistry registry;

    private MySQLContainer<?> mysql;
    private MariaDBContainer<?> mariadb;

    @BeforeAll
    void start() throws Exception {
        mysql = new MySQLContainer<>("mysql:8.4").withPassword("test");
        mariadb = new MariaDBContainer<>("mariadb:11.4").withPassword("test");
        Stream.of(mysql, mariadb).parallel().forEach(JdbcDatabaseContainer::start);
        for (JdbcDatabaseContainer<?> db : new JdbcDatabaseContainer<?>[] {mysql, mariadb}) {
            try (Connection c = db.createConnection(""); Statement s = c.createStatement()) {
                s.execute("create table t (id int primary key)");
            }
        }
    }

    @AfterAll
    void stop() {
        Stream.of(mysql, mariadb).forEach(JdbcDatabaseContainer::stop);
    }

    Stream<Arguments> databases() {
        return Stream.of(
                Arguments.of("postgresql", JdbcDialect.POSTGRESQL, POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                        "opendbapi", POSTGRES.getUsername(), "create table if not exists ro_t (id int)", "insert into ro_t values (1)"),
                Arguments.of("mysql", JdbcDialect.MYSQL, mysql.getHost(), mysql.getMappedPort(3306),
                        mysql.getDatabaseName(), mysql.getUsername(), null, "insert into t values (1)"),
                Arguments.of("mariadb", JdbcDialect.MARIADB, mariadb.getHost(), mariadb.getMappedPort(3306),
                        mariadb.getDatabaseName(), mariadb.getUsername(), null, "insert into t values (1)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void writesFailOnAReadOnlyDatasource(String name, JdbcDialect dialect, String host, int port, String database,
                                         String user, String setup, String write) throws Exception {
        // Arrange
        if (setup != null) {
            try (Connection c = POSTGRES.createConnection(""); Statement s = c.createStatement()) {
                s.execute(setup);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        Datasource ds = new Datasource(UUID.randomUUID(), UUID.randomUUID(), name, null, "jdbc",
                new JdbcConfig(dialect, host, port, database, user, Map.of(), null), "env:RO_TEST_PASSWORD",
                true, null, now, now);

        // Act + Assert: autocommit is on, as for a plain endpoint query
        assertThatThrownBy(() -> {
            try (Connection c = registry.connection(ds); Statement s = c.createStatement()) {
                s.executeUpdate(write);
            }
        }).satisfies(e -> assertThat(e.getMessage()).containsPattern("(?i)read[- ]only"));
        registry.evict(ds.id());
    }
}
