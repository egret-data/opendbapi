package io.opendbapi.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.PostgresTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves the database itself isolates workspaces (spec §4.0): the application connects
 * as a role that cannot bypass row-level security, so a query that forgets the
 * workspace filter still only sees the current workspace's rows.
 */
@SpringBootTest
class WorkspaceIsolationTest extends PostgresTestSupport {

    @Autowired
    private WorkspaceScope scope;

    @Autowired
    private JdbcClient jdbc;

    private UUID sales;
    private UUID finance;

    @BeforeEach
    void createWorkspaces() {
        sales = createWorkspace("sales-" + UUID.randomUUID());
        finance = createWorkspace("finance-" + UUID.randomUUID());
    }

    @Test
    void queryWithoutWorkspaceFilterSeesOnlyCurrentWorkspace() {
        // Arrange
        scope.run(sales, () -> insertDatasource(sales, "sales-db"));
        scope.run(finance, () -> insertDatasource(finance, "finance-db"));

        // Act: deliberately no WHERE workspace_id = ...
        List<String> seenBySales = scope.call(sales, this::allDatasourceNames);

        // Assert
        assertThat(seenBySales).containsExactly("sales-db");
    }

    @Test
    void queryOutsideAnyWorkspaceSeesNothing() {
        // Arrange
        scope.run(sales, () -> insertDatasource(sales, "sales-db"));

        // Act
        List<String> seen = allDatasourceNames();

        // Assert
        assertThat(seen).isEmpty();
    }

    @Test
    void cannotWriteRowIntoAnotherWorkspace() {
        // Arrange: scoped to sales, but the row claims to belong to finance

        // Act + Assert
        assertThatThrownBy(() -> scope.run(sales, () -> insertDatasource(finance, "smuggled")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void workspaceSettingDoesNotLeakPastTheTransaction() {
        // Arrange
        scope.run(sales, () -> insertDatasource(sales, "sales-db"));

        // Act: same pooled connection may be reused after the scoped transaction ends
        List<String> seenAfterwards = allDatasourceNames();

        // Assert
        assertThat(seenAfterwards).isEmpty();
    }

    private UUID createWorkspace(String slug) {
        return jdbc.sql("insert into workspace (slug, name) values (?, ?) returning id")
                .params(slug, slug)
                .query(UUID.class)
                .single();
    }

    private void insertDatasource(UUID workspaceId, String name) {
        jdbc.sql("""
                insert into datasource (workspace_id, name, type, config)
                values (?, ?, 'jdbc', '{}'::jsonb)
                """)
                .params(workspaceId, name)
                .update();
    }

    private List<String> allDatasourceNames() {
        return jdbc.sql("select name from datasource order by name").query(String.class).list();
    }
}
