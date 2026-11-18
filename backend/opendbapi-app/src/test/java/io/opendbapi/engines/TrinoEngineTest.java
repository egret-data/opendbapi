package io.opendbapi.engines;

import io.opendbapi.core.datasource.JdbcDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
        String url = "jdbc:trino://" + host() + ":" + port() + "/memory/default";
        // /v1/info says started before the node scheduler sees a worker for the memory catalog, and even an
        // active row in system.runtime.nodes does not guarantee it: until then statements fail with "No nodes
        // available to run query", without side effects. Retry each seeding statement on that error.
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        retryWhileNoNodes(url, p, deadline, "create table if not exists orders (id bigint, customer varchar, total decimal(10, 2))");
        retryWhileNoNodes(url, p, deadline,
                "insert into orders values (1, 'ana', 10), (2, 'bo', 20), (3, 'cy', 30), (4, 'di', 40), (5, 'ed', 50)");
        // The memory connector keeps data on the worker; read it back before the tests run.
        retryWhileNoNodes(url, p, deadline, "select count(*) from orders");
    }

    private static void retryWhileNoNodes(String url, Properties p, long deadline, String sql) throws Exception {
        while (true) {
            try (Connection c = DriverManager.getConnection(url, p); Statement s = c.createStatement()) {
                s.execute(sql);
                return;
            } catch (SQLException e) {
                boolean noNodes = String.valueOf(e.getMessage()).contains("No nodes available");
                if (!noNodes || System.nanoTime() > deadline) {
                    throw e;
                }
            }
            Thread.sleep(1000);
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
