package io.opendbapi.sql.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.sql.inspect.SqlInspector.Context;
import io.opendbapi.sql.inspect.SqlInspector.Finding;
import io.opendbapi.sql.inspect.SqlInspector.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlInspectorTest {

    private static final Context GET = new Context(true, false);
    private static final Context POST = new Context(false, false);
    private static final Context POST_READ_ONLY_DB = new Context(false, true);

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    @Test
    void aPlainParameterisedSelectIsClean() {
        // Act
        var findings = SqlInspector.inspect("select id, name from users where id = ? order by name limit 10", GET);

        // Assert
        assertThat(findings).isEmpty();
        assertThat(SqlInspector.hasErrors(findings)).isFalse();
    }

    @Test
    void writesAreRefusedOnGetEndpoints() {
        // Act + Assert
        assertThat(codes(SqlInspector.inspect("update users set name = ? where id = ?", GET))).containsExactly("write_in_get");
        assertThat(codes(SqlInspector.inspect("insert into t (a) values (?)", GET))).containsExactly("write_in_get");
        assertThat(codes(SqlInspector.inspect("delete from t where id = ?", GET))).containsExactly("write_in_get");
        assertThat(codes(SqlInspector.inspect("insert into t (a) values (?)", POST))).isEmpty();
    }

    @Test
    void writesAreRefusedOnReadOnlyDatasources() {
        // Act + Assert
        assertThat(codes(SqlInspector.inspect("insert into t (a) values (?)", POST_READ_ONLY_DB)))
                .containsExactly("write_on_read_only");
    }

    @Test
    void updateOrDeleteWithoutWhereIsAnError() {
        // Act + Assert
        assertThat(codes(SqlInspector.inspect("update users set active = false", POST))).containsExactly("missing_where");
        assertThat(codes(SqlInspector.inspect("delete from users", POST))).containsExactly("missing_where");
    }

    @Test
    void multipleStatementsAreAnError() {
        // Act
        var findings = SqlInspector.inspect("select 1; drop table users", POST);

        // Assert
        assertThat(codes(findings)).contains("multiple_statements");
        assertThat(SqlInspector.hasErrors(findings)).isTrue();
    }

    @Test
    void trailingSemicolonIsNotASecondStatement() {
        // Act + Assert
        assertThat(SqlInspector.inspect("select 1;", GET)).isEmpty();
    }

    @Test
    void ddlIsFlaggedEvenWhereWritesAreAllowed() {
        // Act + Assert
        assertThat(codes(SqlInspector.inspect("drop table users", POST))).containsExactly("ddl");
        assertThat(codes(SqlInspector.inspect("truncate table users", POST))).containsExactly("ddl");
        assertThat(codes(SqlInspector.inspect("create table x (a int)", GET))).contains("write_in_get", "ddl");
    }

    @Test
    void selectIntoAndLockingSelectsAreWrites() {
        // Act + Assert
        assertThat(codes(SqlInspector.inspect("select * into backup from users", GET))).contains("write_in_get");
        assertThat(codes(SqlInspector.inspect("select id from jobs where id = ? for update", GET))).containsExactly("locks_rows");
    }

    @Test
    void selectStarIsAWarningOnly() {
        // Act
        var findings = SqlInspector.inspect("select * from users where id = ?", GET);

        // Assert
        assertThat(findings).extracting(Finding::code, Finding::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("select_star", Severity.WARNING));
        assertThat(SqlInspector.hasErrors(findings)).isFalse();
        assertThat(codes(SqlInspector.inspect("select u.* from users u union all select a.* from admins a", GET)))
                .containsExactly("select_star");
    }

    @Test
    void cteSelectsAreReads() {
        // Act + Assert
        assertThat(SqlInspector.inspect("with recent as (select id from orders where ts > ?) select id from recent", GET)).isEmpty();
    }

    @Test
    void unparseableSqlThatWritesIsStillBlockedOnGet() {
        // Arrange: PostgreSQL data-modifying CTE, which the parser does not understand
        String sql = "with gone as (delete from sessions where user_id = ? returning id) select count(*) from gone";

        // Act
        var onGet = SqlInspector.inspect(sql, GET);
        var onPost = SqlInspector.inspect(sql, POST);

        // Assert
        assertThat(codes(onGet)).containsExactly("unparsed", "write_in_get");
        assertThat(SqlInspector.hasErrors(onGet)).isTrue();
        assertThat(codes(onPost)).containsExactly("unparsed");
    }

    @Test
    void keywordFallbackIgnoresStringsAndComments() {
        // Act + Assert
        assertThat(SqlInspector.writeKeyword("select 'please delete me' /* update */ -- drop\n from t")).isNull();
        assertThat(SqlInspector.writeKeyword("select \"update\" from t")).isNull();
        assertThat(SqlInspector.writeKeyword("select x; DELETE from t")).isEqualTo("DELETE");
    }

    @Test
    void unparseableSqlIsAWarningSoDialectSyntaxCanStillPublish() {
        // Act
        var findings = SqlInspector.inspect("select id from t sample block (10) where ??? weird", GET);

        // Assert
        assertThat(codes(findings)).containsExactly("unparsed");
        assertThat(SqlInspector.hasErrors(findings)).isFalse();
    }
}
