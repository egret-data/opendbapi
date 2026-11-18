package io.opendbapi.engines;

import io.opendbapi.core.datasource.JdbcDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** ClickHouse over HTTP, with a MergeTree table in the default database. */
class ClickHouseEngineTest extends EngineContract.WithoutTransactions {

    private GenericContainer<?> clickhouse;

    @BeforeAll
    @SuppressWarnings("resource")
    void start() throws Exception {
        clickhouse = new GenericContainer<>("clickhouse/clickhouse-server:24.8")
                .withEnv("CLICKHOUSE_USER", "opendbapi")
                .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                .withExposedPorts(8123)
                .waitingFor(Wait.forHttp("/ping").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
        clickhouse.start();
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://" + host() + ":" + port() + "/default", username(), PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("create table orders (id Int64, customer String, total Decimal(10, 2)) engine = MergeTree order by id");
            s.execute("insert into orders values (1, 'ana', 10), (2, 'bo', 20), (3, 'cy', 30), (4, 'di', 40), (5, 'ed', 50)");
        }
    }

    @AfterAll
    void stop() {
        clickhouse.stop();
    }

    @Override
    JdbcDialect dialect() {
        return JdbcDialect.CLICKHOUSE;
    }

    @Override
    String host() {
        return clickhouse.getHost();
    }

    @Override
    int port() {
        return clickhouse.getMappedPort(8123);
    }

    @Override
    String database() {
        return "default";
    }

    @Override
    String username() {
        return "opendbapi";
    }

    @Override
    String password() {
        return PASSWORD;
    }

    @Override
    String schema() {
        return "default";
    }
}
