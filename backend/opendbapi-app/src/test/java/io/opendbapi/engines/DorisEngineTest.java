package io.opendbapi.engines;

import io.opendbapi.core.datasource.JdbcDialect;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Doris in one container (FE and BE), through the MySQL driver. */
class DorisEngineTest extends EngineContract.WithoutTransactions {

    private GenericContainer<?> engine;

    @BeforeAll
    @SuppressWarnings("resource")
    void start() throws Exception {
        engine = new GenericContainer<>("apache/doris:all-in-one-4.1.5")
                .withExposedPorts(9030)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));
        engine.start();
        MySqlProtocolEngine.seed(engine, PASSWORD);
    }

    @AfterAll
    void stop() {
        engine.stop();
    }

    @Override
    JdbcDialect dialect() {
        return JdbcDialect.DORIS;
    }

    @Override
    String host() {
        return engine.getHost();
    }

    @Override
    int port() {
        return engine.getMappedPort(9030);
    }

    @Override
    String database() {
        return "shop";
    }

    @Override
    String username() {
        return "root";
    }

    @Override
    String password() {
        return PASSWORD;
    }

    @Override
    String schema() {
        return "shop";
    }
}
