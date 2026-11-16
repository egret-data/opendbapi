package io.opendbapi.sql.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.sql.inspect.SqlInspector;
import io.opendbapi.sql.page.Dialect;
import io.opendbapi.sql.page.Paginator;
import io.opendbapi.sql.template.BoundSql;
import io.opendbapi.sql.template.SqlTemplate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The engine's output, executed for real: templates, bind values and pagination must give
 * the same answers on PostgreSQL and MySQL (spec §24).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDatabaseTest {

    private static final String TEMPLATE = """
            <bind name="pattern" value="'%' + q + '%'"/>
            select id, name, status, amount from orders
            <where>
              <if test="q != null and q != ''">and name like #{pattern}</if>
              <if test="statuses != null and statuses.size() > 0">and status in (#{statuses})</if>
              <if test="minAmount != null">and amount >= #{minAmount}</if>
              <if test="maxAmount != null">and amount < #{maxAmount}</if>
            </where>
            <orderBy param="sort" allow="id, name, amount" default="id"/>""";

    private PostgreSQLContainer<?> postgres;
    private MySQLContainer<?> mysql;

    @BeforeAll
    void start() throws SQLException {
        postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
        mysql = new MySQLContainer<>("mysql:8.4");
        Stream.of(postgres, mysql).parallel().forEach(JdbcDatabaseContainer::start);
        for (JdbcDatabaseContainer<?> db : List.of(postgres, mysql)) {
            try (Connection c = connect(db); Statement s = c.createStatement()) {
                s.execute("create table orders (id int primary key, name varchar(50), status varchar(10), amount decimal(10,2))");
                StringBuilder insert = new StringBuilder("insert into orders values ");
                for (int i = 1; i <= 25; i++) {
                    String status = i % 3 == 0 ? "shipped" : i % 3 == 1 ? "new" : "paid";
                    insert.append(i > 1 ? "," : "")
                            .append("(%d, 'order %02d%s', '%s', %d.50)".formatted(i, i, i % 5 == 0 ? " acme" : "", status, i * 10));
                }
                s.execute(insert.toString());
            }
        }
    }

    @AfterAll
    void stop() {
        Stream.of(postgres, mysql).filter(java.util.Objects::nonNull).forEach(JdbcDatabaseContainer::stop);
    }

    Stream<Arguments> databases() {
        return Stream.of(Arguments.of("postgresql", postgres, Dialect.POSTGRESQL), Arguments.of("mysql", mysql, Dialect.MYSQL));
    }

    private static Connection connect(JdbcDatabaseContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static List<Integer> ids(JdbcDatabaseContainer<?> db, BoundSql sql) throws SQLException {
        try (Connection c = connect(db); PreparedStatement ps = c.prepareStatement(sql.sql())) {
            for (int i = 0; i < sql.parameters().size(); i++) {
                ps.setObject(i + 1, sql.parameters().get(i));
            }
            List<Integer> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
            return out;
        }
    }

    private static long count(JdbcDatabaseContainer<?> db, BoundSql sql) throws SQLException {
        return ids(db, sql).get(0);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @ParameterizedTest(name = "{0}", autoCloseArguments = false) // the containers are shared
    @MethodSource("databases")
    void filtersInListsAndRangesGiveTheSameRows(String name, JdbcDatabaseContainer<?> db, Dialect dialect) throws SQLException {
        // Arrange
        SqlTemplate template = SqlTemplate.compile(TEMPLATE);

        // Act
        BoundSql sql = template.render(params("statuses", List.of("new", "paid"), "minAmount", 50, "maxAmount", 120,
                "sort", "amount desc"));

        // Assert: ids 5..11 by amount (55.50..115.50), minus shipped (6, 9), highest first
        assertThat(ids(db, sql)).containsExactly(11, 10, 8, 7, 5);
        assertThat(SqlInspector.inspect(sql.sql(), new SqlInspector.Context(true, true))).isEmpty();
    }

    @ParameterizedTest(name = "{0}", autoCloseArguments = false) // the containers are shared
    @MethodSource("databases")
    void paginationPagesAndCountsTheFilteredRows(String name, JdbcDatabaseContainer<?> db, Dialect dialect) throws SQLException {
        // Arrange
        BoundSql filtered = SqlTemplate.compile(TEMPLATE).render(params("statuses", List.of("new")));

        // Act
        Paginator.Paged page2 = Paginator.paginate(filtered, dialect, 2, 3);
        Paginator.Paged last = Paginator.paginate(filtered, dialect, 3, 3);

        // Assert: "new" orders are 1,4,7,10,13,16,19,22,25
        assertThat(ids(db, page2.data())).containsExactly(10, 13, 16);
        assertThat(ids(db, last.data())).containsExactly(19, 22, 25);
        assertThat(count(db, page2.count())).isEqualTo(9);
    }

    @ParameterizedTest(name = "{0}", autoCloseArguments = false) // the containers are shared
    @MethodSource("databases")
    void likeSearchAndEmptyInList(String name, JdbcDatabaseContainer<?> db, Dialect dialect) throws SQLException {
        // Act
        List<Integer> acme = ids(db, SqlTemplate.compile(TEMPLATE).render(params("q", "acme")));
        List<Integer> none = ids(db, SqlTemplate.compile("select id from orders where id in (#{ids})").render(params("ids", List.of())));

        // Assert
        assertThat(acme).containsExactly(5, 10, 15, 20, 25);
        assertThat(none).isEmpty();
    }

    @ParameterizedTest(name = "{0}", autoCloseArguments = false) // the containers are shared
    @MethodSource("databases")
    void injectionAttemptsAreJustValues(String name, JdbcDatabaseContainer<?> db, Dialect dialect) throws SQLException {
        // Act
        List<Integer> rows = ids(db, SqlTemplate.compile(TEMPLATE).render(params("q", "' or '1'='1")));

        // Assert: matched literally, nothing returned, table intact
        assertThat(rows).isEmpty();
        assertThat(count(db, new BoundSql("select count(*) from orders", List.of()))).isEqualTo(25);
    }

    @Test
    void sqlServerPaginationIsAtLeastValidSyntax() throws Exception {
        // No SQL Server container (heavy, x86 only): check the generated SQL parses as T-SQL-compatible.
        Paginator.Paged p = Paginator.paginate(new BoundSql("select id from orders", List.of()), Dialect.SQLSERVER, 2, 10);
        net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(p.data().sql());
        net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(p.count().sql());
    }
}
