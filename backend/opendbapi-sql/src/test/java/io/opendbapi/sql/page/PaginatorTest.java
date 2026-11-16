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
        assertThat(Dialect.fromJdbcUrl("jdbc:something://h")).isEqualTo(Dialect.OTHER);
    }
}
