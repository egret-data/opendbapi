package io.opendbapi.engines;

import io.opendbapi.core.datasource.JdbcDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Trino, without authentication, on its in-memory catalog. */
class TrinoEngineTest extends EngineContract {

    private GenericContainer<?> trino;

    @BeforeAll
    @SuppressWarnings("resource")
    void start() throws Exception {
        trino = new GenericContainer<>("trinodb/trino:476")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/v1/info").forResponsePredicate(b -> b.contains("\"starting\":false"))
                        .withStartupTimeout(Duration.ofMinutes(3)));
        trino.start();
        Properties p = new Properties();
        p.setProperty("user", "seed");
        try (Connection c = DriverManager.getConnection("jdbc:trino://" + host() + ":" + port() + "/memory/default", p);
             Statement s = c.createStatement()) {
            s.execute("create table orders (id bigint, customer varchar, total decimal(10, 2))");
            s.execute("insert into orders values (1, 'ana', 10), (2, 'bo', 20), (3, 'cy', 30), (4, 'di', 40), (5, 'ed', 50)");
        }
    }

    @AfterAll
    void stop() {
        trino.stop();
    }

    @Override
    JdbcDialect dialect() {
        return JdbcDialect.TRINO;
    }

    @Override
    String host() {
        return trino.getHost();
    }

    @Override
    int port() {
        return trino.getMappedPort(8080);
    }

    @Override
    String database() {
        return "memory.default";
    }

    @Override
    String username() {
        return "opendbapi";
    }

    @Override
    String password() {
        return null;
    }

    @Override
    String schema() {
        return "default";
    }
}
