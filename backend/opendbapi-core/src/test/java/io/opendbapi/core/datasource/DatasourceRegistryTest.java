package io.opendbapi.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.PostgresTestSupport;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceScope;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Pools, connection tests and schema browsing against a real PostgreSQL. The test container
 * doubles as the "customer database": schema {@code shop} is the data an endpoint would query.
 */
@SpringBootTest
@TestPropertySource(properties = "TEST_SHOP_PASSWORD=test")
class DatasourceRegistryTest extends PostgresTestSupport {

    @Autowired DataSourceRegistry registry;
    @Autowired DatasourceStore store;
    @Autowired WorkspaceScope scope;
    @Autowired JdbcClient jdbc;

    private UUID workspace;

    @BeforeAll
    static void createShopSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create schema if not exists shop");
            s.execute("create table if not exists shop.orders (id int primary key, customer text not null, amount numeric(10,2))");
            s.execute("create or replace view shop.big_orders as select * from shop.orders where amount > 100");
            s.execute("insert into shop.orders values (1, 'acme', 50), (2, 'globex', 150) on conflict do nothing");
        }
    }

    @BeforeEach
    void createWorkspace() {
        workspace = jdbc.sql("insert into workspace (slug, name) values (?, 'Shop') returning id")
                .param("shop-" + UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class).single();
    }

    private JdbcConfig shopConfig() {
        return new JdbcConfig(JdbcDialect.POSTGRESQL, POSTGRES.getHost(), POSTGRES.getMappedPort(5432), "opendbapi",
                POSTGRES.getUsername(), Map.of("currentSchema", "shop"), null);
    }

    private Datasource save(boolean readOnly) {
        return scope.call(workspace, () -> store.create(workspace, "shop-db", null, shopConfig(), "env:TEST_SHOP_PASSWORD", readOnly, null));
    }

    @Test
    void pooledConnectionsQueryTheCustomerDatabase() throws Exception {
        // Arrange
        Datasource ds = save(false);

        // Act
        try (Connection c = registry.connection(ds); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from orders")) {
            rs.next();

            // Assert
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void readOnlyDatasourcesCannotWrite() {
        // Arrange
        Datasource ds = save(true);

        // Act + Assert
        assertThatThrownBy(() -> {
            try (Connection c = registry.connection(ds); Statement s = c.createStatement()) {
                s.execute("insert into orders values (99, 'x', 1)");
            }
        }).hasMessageContaining("read-only");
    }

    @Test
    void changingTheDatasourceReplacesItsPool() throws Exception {
        // Arrange
        Datasource ds = save(false);
        try (Connection ignored = registry.connection(ds)) {
            assertThat(registry.isPooled(ds.id())).isTrue();
        }

        // Act
        Datasource updated = scope.call(workspace, () -> store.update(ds.id(), "shop-db", "renamed", shopConfig(), null, true));
        registry.evict(ds.id());

        // Assert
        assertThat(registry.isPooled(ds.id())).isFalse();
        assertThat(updated.readOnly()).isTrue();
    }

    @Test
    void testConnectionReportsTheServer() {
        // Act
        var result = registry.test(shopConfig(), "test");

        // Assert
        assertThat(result.product()).isEqualTo("PostgreSQL");
        assertThat(result.version()).startsWith("16.");
        assertThat(result.latencyMs()).isNotNegative();
    }

    @Test
    void wrongPasswordIsAConnectionProblem() {
        // Act + Assert
        assertThatThrownBy(() -> registry.test(shopConfig(), "wrong"))
                .isInstanceOf(ApiProblem.class)
                .extracting("code").isEqualTo("connection_failed");
    }

    @Test
    void blockedHostsAreRefusedBeforeConnecting() {
        // Arrange
        JdbcConfig metadata = new JdbcConfig(JdbcDialect.POSTGRESQL, "169.254.169.254", 5432, "x", "u", Map.of(), null);

        // Act + Assert
        assertThatThrownBy(() -> registry.test(metadata, "p"))
                .isInstanceOf(ApiProblem.class)
                .extracting("code").isEqualTo("host_not_allowed");
    }

    @Test
    void browsesSchemasTablesAndColumns() {
        // Arrange
        Datasource ds = save(false);

        // Act
        var schemas = registry.schemas(ds);
        var tables = registry.tables(ds, "shop");
        var columns = registry.columns(ds, "shop", "orders");

        // Assert
        assertThat(schemas).contains("shop", "public").doesNotContain("pg_catalog", "information_schema");
        assertThat(tables.items()).extracting(DataSourceRegistry.Table::name, DataSourceRegistry.Table::type)
                .contains(org.assertj.core.groups.Tuple.tuple("orders", "TABLE"),
                        org.assertj.core.groups.Tuple.tuple("big_orders", "VIEW"));
        assertThat(columns).extracting(DataSourceRegistry.TableColumn::name).containsExactly("id", "customer", "amount");
        assertThat(columns.get(1).nullable()).isFalse();
        assertThat(columns.get(2).type()).isEqualTo("numeric");
    }

    @Test
    void storeIsWorkspaceScopedAndHidesNothingButTheSecret() {
        // Arrange
        Datasource ds = save(false);

        // Act
        var listed = scope.call(workspace, store::list);
        var elsewhere = scope.call(createOtherWorkspace(), store::list);

        // Assert
        assertThat(listed).extracting(Datasource::id).containsExactly(ds.id());
        assertThat(listed.get(0).config().properties()).containsEntry("currentSchema", "shop");
        assertThat(elsewhere).isEmpty();
    }

    private UUID createOtherWorkspace() {
        return jdbc.sql("insert into workspace (slug, name) values (?, 'Other') returning id")
                .param("other-" + UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class).single();
    }
}
