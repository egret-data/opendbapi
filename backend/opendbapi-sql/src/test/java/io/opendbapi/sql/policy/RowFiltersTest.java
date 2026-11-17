package io.opendbapi.sql.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.sql.template.BoundSql;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowFiltersTest {

    private static BoundSql q(String sql, Object... params) {
        return new BoundSql(sql, List.of(params));
    }

    @Test
    void addsTheConditionToWhereAndKeepsOrderBy() {
        // Act
        BoundSql out = RowFilters.apply(q("select id, tenant_id from orders where status = ? order by id desc", "open"),
                "tenant_id", List.of("acme"));

        // Assert
        assertThat(out.sql()).isEqualTo("SELECT id, tenant_id FROM orders WHERE (status = ?) AND tenant_id = ? ORDER BY id DESC");
        assertThat(out.parameters()).containsExactly("open", "acme");
    }

    @Test
    void filtersOnTheExpressionBehindAnAlias() {
        // Act
        BoundSql out = RowFilters.apply(q("select o.id, o.org as tenant from orders o"), "tenant", List.of(7L));

        // Assert
        assertThat(out.sql()).isEqualTo("SELECT o.id, o.org AS tenant FROM orders o WHERE o.org = ?");
        assertThat(out.parameters()).containsExactly(7L);
    }

    @Test
    void placesTheParameterBetweenEarlierAndLaterOnes() {
        // Arrange: a parameter in the select list before WHERE and one in LIMIT after it
        BoundSql query = q("select ? as label, tenant_id from t where a = ? limit ?", "x", 1, 10);

        // Act
        BoundSql out = RowFilters.apply(query, "tenant_id", List.of("acme"));

        // Assert
        assertThat(out.parameters()).containsExactly("x", 1, "acme", 10);
        assertThat(out.sql()).contains("WHERE (a = ?) AND tenant_id = ? LIMIT ?");
    }

    @Test
    void severalValuesBecomeInAndNoneMatchesNothing() {
        // Act
        BoundSql many = RowFilters.apply(q("select tenant_id from t"), "tenant_id", List.of("a", "b"));
        BoundSql none = RowFilters.apply(q("select tenant_id from t"), "tenant_id", List.of());

        // Assert
        assertThat(many.sql()).isEqualTo("SELECT tenant_id FROM t WHERE tenant_id IN (?, ?)");
        assertThat(many.parameters()).containsExactly("a", "b");
        assertThat(none.sql()).isEqualTo("SELECT tenant_id FROM t WHERE 1 = 0");
        assertThat(none.parameters()).isEmpty();
    }

    @Test
    void filtersEveryBranchOfAUnion() {
        // Act
        BoundSql out = RowFilters.apply(q("select tenant_id, 'a' from a union all select tenant_id, 'b' from b"), "tenant_id",
                List.of("acme"));

        // Assert
        assertThat(out.sql()).isEqualTo(
                "SELECT tenant_id, 'a' FROM a WHERE tenant_id = ? UNION ALL SELECT tenant_id, 'b' FROM b WHERE tenant_id = ?");
        assertThat(out.parameters()).containsExactly("acme", "acme");
    }

    @Test
    void starOverOneTableQualifiesTheColumn() {
        // Act
        BoundSql out = RowFilters.apply(q("select * from sales.orders o order by id"), "tenant_id", List.of("acme"));

        // Assert
        assertThat(out.sql()).isEqualTo("SELECT * FROM sales.orders o WHERE o.tenant_id = ? ORDER BY id");
    }

    @Test
    void starOverAJoinIsWrappedWhenThereIsNoOrderBy() {
        // Act
        BoundSql out = RowFilters.apply(q("select * from a join b on a.id = b.a_id where a.x = ?", 1), "tenant_id",
                List.of("acme"));

        // Assert
        assertThat(out.sql()).isEqualTo(
                "SELECT * FROM (select * from a join b on a.id = b.a_id where a.x = ?) opendbapi_rf WHERE opendbapi_rf.tenant_id = ?");
        assertThat(out.parameters()).containsExactly(1, "acme");
    }

    @Test
    void refusesWhatItCannotFilterSafely() {
        // Assert: ordered star over a join, aggregate column, unparsable SQL, a write, a bad column name
        assertThatThrownBy(() -> RowFilters.apply(q("select * from a join b on a.id = b.id order by a.id"), "tenant_id", List.of(1)))
                .isInstanceOf(RowFilters.UnsupportedException.class).hasMessageContaining("ORDER BY");
        assertThatThrownBy(() -> RowFilters.apply(q("select count(*) as tenant_id from t"), "tenant_id", List.of(1)))
                .isInstanceOf(RowFilters.UnsupportedException.class).hasMessageContaining("aggregate");
        assertThatThrownBy(() -> RowFilters.apply(q("select from where"), "tenant_id", List.of(1)))
                .isInstanceOf(RowFilters.UnsupportedException.class);
        assertThatThrownBy(() -> RowFilters.apply(q("delete from t"), "tenant_id", List.of(1)))
                .isInstanceOf(RowFilters.UnsupportedException.class);
        assertThatThrownBy(() -> RowFilters.apply(q("select 1"), "x; drop table t", List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placeholderCountIgnoresLiteralsAndComments() {
        // Act
        String sql = "select '?', \"a?\", `b?` -- ?\n /* ? */ from t where x = ?";
        int n = RowFilters.Placeholders.countBefore(sql, sql.length());

        // Assert
        assertThat(n).isEqualTo(1);
    }
}
