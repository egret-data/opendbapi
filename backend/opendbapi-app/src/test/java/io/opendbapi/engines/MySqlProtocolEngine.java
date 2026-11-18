package io.opendbapi.engines;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.testcontainers.containers.GenericContainer;

/** StarRocks and Doris: one FE and one BE in a container, reached over the MySQL protocol. */
final class MySqlProtocolEngine {

    private MySqlProtocolEngine() {
    }

    /**
     * Waits until the backend takes writes (the FE answers long before), then creates
     * {@code shop.orders} with five rows and sets the root password.
     */
    static void seed(GenericContainer<?> engine, String password) throws Exception {
        String url = "jdbc:mysql://" + engine.getHost() + ":" + engine.getMappedPort(9030) + "/";
        List<String> statements = List.of(
                "create database if not exists shop",
                "create table if not exists shop.orders (id bigint, customer varchar(20), total decimal(10, 2)) "
                        + "duplicate key(id) distributed by hash(id) buckets 1 properties ('replication_num' = '1')",
                "insert into shop.orders values (1, 'ana', 10), (2, 'bo', 20), (3, 'cy', 30), (4, 'di', 40), (5, 'ed', 50)");
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection c = DriverManager.getConnection(url, "root", ""); Statement s = c.createStatement()) {
                for (String sql : statements) {
                    s.execute(sql);
                }
                s.execute("set password for 'root' = password('" + password + "')");
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(3000);
            }
        }
        throw new IllegalStateException("The engine did not take writes within 5 minutes", last);
    }
}
