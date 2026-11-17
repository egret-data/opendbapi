package io.opendbapi.sql.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.sql.policy.ColumnLineage.Lineage;
import io.opendbapi.sql.policy.ColumnLineage.Source;
import org.junit.jupiter.api.Test;

class ColumnLineageTest {

    private static Lineage trace(String sql) {
        return ColumnLineage.trace(sql).orElseThrow();
    }

    @Test
    void followsAliasesAndExpressionsToTableColumns() {
        // Act
        Lineage l = trace("select c.email as contact, upper(c.name) as shout, o.total from customers c join orders o on o.cid = c.id");

        // Assert
        assertThat(l.column("contact").orElseThrow().sources()).containsExactly(new Source("customers", "email"));
        assertThat(l.column("shout").orElseThrow().sources()).containsExactly(new Source("customers", "name"));
        assertThat(l.column("total").orElseThrow().sources()).containsExactly(new Source("orders", "total"));
    }

    @Test
    void followsDerivedTablesAndUnionBranches() {
        // Act
        Lineage derived = trace("select x.e from (select email as e from customers) x");
        Lineage union = trace("select email as e from customers union select mail from staff");

        // Assert
        assertThat(derived.column("e").orElseThrow().sources()).containsExactly(new Source("customers", "email"));
        assertThat(union.column("e").orElseThrow().sources())
                .containsExactlyInAnyOrder(new Source("customers", "email"), new Source("staff", "mail"));
    }

    @Test
    void marksStarCtesAndSubqueries() {
        // Act
        Lineage star = trace("select * from customers");
        Lineage cte = trace("with c as (select email from customers) select email from c");
        Lineage sub = trace("select (select max(email) from customers) as m from dual");

        // Assert
        assertThat(star.star()).isTrue();
        assertThat(cte.column("email").orElseThrow().sources()).containsExactly(new Source(null, "email"));
        assertThat(sub.column("m").orElseThrow().opaque()).isTrue();
    }

    @Test
    void unparsableSqlHasNoLineage() {
        // Assert
        assertThat(ColumnLineage.trace("select from where")).isEmpty();
        assertThat(ColumnLineage.trace("delete from t")).isEmpty();
    }
}
