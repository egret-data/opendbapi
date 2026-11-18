package io.opendbapi.sql.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.sql.template.BoundSql;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaginatorTest {

    private static BoundSql q(String sql, Object... params) {
        return new BoundSql(sql, List.of(params));
    }

    @Test
    void appendsLimitOffsetForPostgresAndMysql() {
        // Act
        Paginator.Paged p = Paginator.paginate(q("select id from users where active = ? order by id", true),
                Dialect.POSTGRESQL, 3, 20);

        // Assert
        assertThat(p.data().sql()).isEqualTo("select id from users where active = ? order by id LIMIT ? OFFSET ?");
        assertThat(p.data().parameters()).containsExactly(true, 20, 40L);
        assertThat(Paginator.paginate(q("select 1"), Dialect.MYSQL, 1, 5).data().sql()).isEqualTo("select 1 LIMIT ? OFFSET ?");
    }

    @Test
    void countDropsOrderByAndKeepsParameters() {
        // Act
        Paginator.Paged p = Paginator.paginate(q("select id from users where active = ? order by name", true),
                Dialect.POSTGRESQL, 1, 10);

        // Assert
        assertThat(p.count().sql()).isEqualTo("SELECT COUNT(*) FROM (SELECT id FROM users WHERE active = ?) count_q");
        assertThat(p.count().parameters()).containsExactly(true);
    }

    @Test
    void wrapsQueriesThatAlreadyLimitThemselves() {
        // Act
        Paginator.Paged p = Paginator.paginate(q("select id from users order by id desc limit 100"), Dialect.POSTGRESQL, 2, 10);

        // Assert
        assertThat(p.data().sql())
                .isEqualTo("SELECT * FROM (select id from users order by id desc limit 100) page_q LIMIT ? OFFSET ?");
        assertThat(p.count().sql())
                .as("the inner LIMIT decides the total, so it must stay")
                .isEqualTo("SELECT COUNT(*) FROM (select id from users order by id desc limit 100) count_q");
    }

    @Test
    void sqlServerUsesOffsetFetchAndNeedsAnOrderBy() {
        // Act
        Paginator.Paged unordered = Paginator.paginate(q("select id from users"), Dialect.SQLSERVER, 2, 10);
        Paginator.Paged ordered = Paginator.paginate(q("select id from users order by id"), Dialect.SQLSERVER, 2, 10);

        // Assert
        assertThat(unordered.data().sql())
                .isEqualTo("select id from users ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(unordered.data().parameters()).containsExactly(10L, 10);
        assertThat(ordered.data().sql()).isEqualTo("select id from users order by id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
    }

    @Test
    void oracleUsesOffsetFetchWithoutForcingAnOrder() {
        // Act + Assert
        assertThat(Paginator.paginate(q("select id from t"), Dialect.ORACLE, 1, 5).data().sql())
                .isEqualTo("select id from t OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
    }

    @Test
    void trinoPutsTheOffsetBeforeTheRowCount() {
        // Act
        Paginator.Paged paged = Paginator.paginate(q("select id from t order by id"), Dialect.TRINO, 3, 5);

        // Assert: Trino rejects LIMIT … OFFSET
        assertThat(paged.data().sql()).isEqualTo("select id from t order by id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(paged.data().parameters()).containsExactly(10L, 5);
    }

    @Test
    void starRocksDorisAndClickHouseUseLimitOffset() {
        // Act + Assert
        for (Dialect d : new Dialect[] {Dialect.STARROCKS, Dialect.DORIS, Dialect.CLICKHOUSE}) {
            assertThat(Paginator.paginate(q("select id from t"), d, 1, 5).data().sql()).isEqualTo("select id from t LIMIT ? OFFSET ?");
        }
    }

    @Test
    void trailingSemicolonsAndWhitespaceAreIgnored() {
        // Act + Assert
        assertThat(Paginator.paginate(q("select 1 ;\n "), Dialect.POSTGRESQL, 1, 5).data().sql()).isEqualTo("select 1 LIMIT ? OFFSET ?");
    }

    @Test
    void unparseableSqlIsWrappedRatherThanEdited() {
        // Act
        Paginator.Paged p = Paginator.paginate(q("select id from t where x = ? @@@ vendor_only", 1), Dialect.OTHER, 1, 5);

        // Assert
        assertThat(p.data().sql()).isEqualTo("SELECT * FROM (select id from t where x = ? @@@ vendor_only) page_q LIMIT ? OFFSET ?");
        assertThat(p.count().sql()).isEqualTo("SELECT COUNT(*) FROM (select id from t where x = ? @@@ vendor_only) count_q");
    }

    @Test
    void parameterisedOrderByIsKeptInTheCount() {
        // Act
        Paginator.Paged p = Paginator.paginate(q("select id from t order by abs(id - ?)", 5), Dialect.POSTGRESQL, 1, 5);

        // Assert
        assertThat(p.count().parameters()).containsExactly(5);
        assertThat(p.count().sql()).contains("order by abs(id - ?)");
    }

    @Test
    void pageAndSizeAreValidated() {
        // Act + Assert
        assertThatThrownBy(() -> Paginator.paginate(q("select 1"), Dialect.POSTGRESQL, 0, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> Paginator.paginate(q("select 1"), Dialect.POSTGRESQL, 1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
    }

    @Test
    void dialectFromJdbcUrl() {
        // Act + Assert
        assertThat(Dialect.fromJdbcUrl("jdbc:postgresql://h/db")).isEqualTo(Dialect.POSTGRESQL);
        assertThat(Dialect.fromJdbcUrl("jdbc:mysql://h/db")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.fromJdbcUrl("jdbc:mariadb://h/db")).isEqualTo(Dialect.MARIADB);
        assertThat(Dialect.fromJdbcUrl("jdbc:sqlserver://h;databaseName=x")).isEqualTo(Dialect.SQLSERVER);
        assertThat(Dialect.fromJdbcUrl("jdbc:oracle:thin:@h:1521/x")).isEqualTo(Dialect.ORACLE);
        assertThat(Dialect.fromJdbcUrl("jdbc:clickhouse://h")).isEqualTo(Dialect.CLICKHOUSE);
        assertThat(Dialect.fromJdbcUrl("jdbc:trino://h:8080/hive")).isEqualTo(Dialect.TRINO);
        assertThat(Dialect.fromJdbcUrl("jdbc:something://h")).isEqualTo(Dialect.OTHER);
    }

    @Test
    void keysetFirstPageOrdersByTheKeysInsteadOfTheQuery() {
        // Act
        BoundSql b = Paginator.keyset(q("select id, created_at from orders where status = ? order by name", "open"), Dialect.POSTGRESQL,
                List.of(new Paginator.Key("created_at", true), new Paginator.Key("id", false)), null, 21);

        // Assert
        assertThat(b.sql()).isEqualTo("SELECT * FROM (SELECT id, created_at FROM orders WHERE status = ?) keyset_q "
                + "ORDER BY keyset_q.created_at DESC, keyset_q.id ASC LIMIT ?");
        assertThat(b.parameters()).containsExactly("open", 21);
    }

    @Test
    void keysetLaterPagesStartAfterTheCursorPortably() {
        // Act
        BoundSql b = Paginator.keyset(q("select id, created_at from orders"), Dialect.SQLSERVER,
                List.of(new Paginator.Key("created_at", true), new Paginator.Key("id", false)), List.of("2026-01-01", 7L), 11);

        // Assert
        assertThat(b.sql()).isEqualTo("SELECT * FROM (select id, created_at from orders) keyset_q WHERE (keyset_q.created_at < ?) "
                + "OR (keyset_q.created_at = ? AND keyset_q.id > ?) ORDER BY keyset_q.created_at DESC, keyset_q.id ASC "
                + "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(b.parameters()).containsExactly("2026-01-01", "2026-01-01", 7L, 11);
    }

    @Test
    void keysetRefusesQueriesItCannotPage() {
        // Assert
        List<Paginator.Key> id = List.of(new Paginator.Key("id", false));
        assertThatThrownBy(() -> Paginator.keyset(q("select id from t limit 5"), Dialect.POSTGRESQL, id, null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limits itself");
        assertThatThrownBy(() -> Paginator.keyset(q("select id from t order by ?", "id"), Dialect.POSTGRESQL, id, null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parameter");
        assertThatThrownBy(() -> new Paginator.Key("id; drop", false)).isInstanceOf(IllegalArgumentException.class);
    }
}
