package io.opendbapi.core.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full mode: db, notFound and badRequest, nothing more (spec §7.4). */
class FullScriptSandboxTest {

    private final ScriptSandbox sandbox = new ScriptSandbox(500);

    /** Records queries and answers each with one row. */
    private static final class FakeDb implements DbAccess {
        final List<String> queries = new ArrayList<>();

        @Override
        public Map<String, Object> one(SqlFragment q) {
            queries.add(q.toBoundSql().sql());
            return Map.of("id", 7);
        }

        @Override
        public List<Map<String, Object>> list(SqlFragment q) {
            queries.add(q.toBoundSql().sql());
            return List.of(Map.of("id", 1), Map.of("id", 2));
        }

        @Override
        public Object value(SqlFragment q) {
            queries.add(q.toBoundSql().sql());
            return 42;
        }

        @Override
        public long update(SqlFragment q) {
            queries.add(q.toBoundSql().sql());
            return 1;
        }
    }

    @Test
    void runsQueriesThroughDbAndReturnsAnyValue() {
        // Arrange
        FakeDb db = new FakeDb();
        var script = sandbox.compile("""
                def u = db.one(sql"select * from users where id = ${params.id}")
                [user: u, ids: db.list(sql"select id from t").collect { it.id }, n: db.value(sql"select 42")]
                """, ScriptSandbox.ScriptMode.FULL);

        // Act
        Object result = script.run(Map.of("id", 7), db);

        // Assert
        assertThat(result).isEqualTo(Map.of("user", Map.of("id", 7), "ids", List.of(1, 2), "n", 42));
        assertThat(db.queries).containsExactly("select * from users where id = ?", "select id from t", "select 42");
        assertThat(script.parameterNames()).containsExactly("id");
    }

    @Test
    void notFoundAndBadRequestEndTheRequest() {
        // Arrange
        var script = sandbox.compile("if (!params.id) badRequest('id is required')\nnotFound(\"no ${params.id}\")", ScriptSandbox.ScriptMode.FULL);

        // Act + Assert
        assertThatThrownBy(() -> script.run(Map.of(), new FakeDb())).isInstanceOf(ScriptAnswer.class)
                .satisfies(e -> assertThat(((ScriptAnswer) e).status()).isEqualTo(400));
        assertThatThrownBy(() -> script.run(Map.of("id", 3), new FakeDb())).isInstanceOf(ScriptAnswer.class)
                .hasMessage("no 3").satisfies(e -> assertThat(((ScriptAnswer) e).code()).isEqualTo("not_found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "db.connection",
            "db.getClass()",
            "db.execute(sql\"x\")",
            "db?.one(sql\"x\")",
            "def db = 1",
            "db = [:]",
            "params = [:]",
            "def x = db\nx.one(sql\"x\")",
            "System.exit(0)",
    })
    void refusesWhatReachesBeyondDb(String source) {
        // Assert
        assertThatThrownBy(() -> sandbox.compile(source, ScriptSandbox.ScriptMode.FULL)).isInstanceOf(ScriptRejected.class);
    }

    @Test
    void fragmentScriptsHaveNoDbHelpers() {
        // Assert
        assertThatThrownBy(() -> sandbox.compile("notFound('x')")).isInstanceOf(ScriptRejected.class);
    }
}
