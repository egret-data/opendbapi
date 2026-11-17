package io.opendbapi.core.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.sql.template.BoundSql;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ScriptSandboxTest {

    private final ScriptSandbox sandbox = new ScriptSandbox(500);

    private BoundSql render(String source, Map<String, Object> params) {
        return sandbox.compile(source).render(params);
    }

    @Test
    void buildsBoundSqlFromFragments() {
        // Arrange
        String source = """
                def q = sql"select id, name from users where 1 = 1"
                if (params.name) q += sql" and name ilike ${'%' + params.name + '%'}"
                if (params.ids) q += sql" and id in (${params.ids})"
                q += orderBy([newest: 'created_at desc', name: 'name asc'], params.sort, 'id desc')
                return q
                """;

        // Act
        BoundSql b = render(source, Map.of("name", "an", "ids", List.of(1L, 2L), "sort", "newest"));

        // Assert
        assertThat(b.sql()).isEqualTo("select id, name from users where 1 = 1 and name ilike ? and id in (?, ?) order by created_at desc");
        assertThat(b.parameters()).containsExactly("%an%", 1L, 2L);
    }

    @Test
    void valuesNeverBecomeSqlText() {
        // Assert: injection attempts stay bind values; strings cannot be added; raw only takes literals
        BoundSql b = render("return sql\"select * from t where a = ${params.x}\"", Map.of("x", "1; drop table t"));
        assertThat(b.sql()).isEqualTo("select * from t where a = ?");
        assertThat(b.parameters()).containsExactly("1; drop table t");
        assertThatThrownBy(() -> render("return sql\"select 1\" + params.x", Map.of("x", " or 1=1")))
                .isInstanceOf(ScriptRejected.class).hasMessageContaining("Only sql");
        assertThatThrownBy(() -> sandbox.compile("return raw(params.x)")).isInstanceOf(ScriptRejected.class).hasMessageContaining("raw()");
        assertThat(render("return raw('select 1')", Map.of()).sql()).isEqualTo("select 1");
        assertThat(render("return orderBy([a: 'x'], params.s, 'id')", Map.of("s", "x; drop")).sql()).isEqualTo(" order by id");
    }

    @Test
    void mustReturnAFragment() {
        // Assert
        assertThatThrownBy(() -> render("return 'select 1'", Map.of())).isInstanceOf(ScriptRejected.class).hasMessageContaining("String");
        assertThatThrownBy(() -> render("return \"select ${1}\"", Map.of())).isInstanceOf(ScriptRejected.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Runtime.getRuntime().exec('id'); return sql'x'",
            "'id'.execute(); return sql'x'",
            "System.exit(1)",
            "def c = ''.getClass(); return sql'x'",
            "def c = ''.class; return sql'x'",
            "def k = 'cla' + 'ss'; def c = ''[k]; return sql'x'",
            "''.\"${'getClass'}\"(); return sql'x'",
            "new File('/etc/passwd').text",
            "java.io.File.createTempFile('a', 'b')",
            "import java.nio.file.Files\nreturn sql'x'",
            "@Grab('x:y:1') import foo.Bar\nreturn sql'x'",
            "class Evil {}\nreturn sql'x'",
            "def f() { 1 }\nreturn sql'x'",
            "this.binding.variables; return sql'x'",
            "binding; return sql'x'",
            "evaluate('1'); return sql'x'",
            "params.metaClass; return sql'x'",
            "def m = ''.&toString; return sql'x'",
            "Class.forName('java.lang.Runtime')",
            "'x'.with { it }",
            "sleep(10000)",
            "def t = Thread.currentThread()",
            "def x = (Runtime) null",
            "{ -> 1 }.delegate",
            "Eval.me('1')",
            // found by review: Groovy's URL helpers on String, Class via TYPE, caller text in orderBy
            "return sql\"x ${'file:///etc/hosts'.toURL().text}\"",
            "return sql\"x ${'file:///etc/hosts'.toURI().toURL().getText()}\"",
            "'file:///etc/hosts'.toURL().openStream()",
            "def k = Integer.TYPE; return sql\"x ${k.name}\"",
            "Integer.TYPE.getResourceAsStream('/x')",
            "return orderBy([a: params.x], params.s, 'id')",
            "return orderBy(params.m, params.s, 'id')",
            "return orderBy([a: 'x'], params.s, params.d)",
            "''.readLines()",
            "[1].dump()",
    })
    void refusesWhatCouldEscape(String source) {
        // Assert
        assertThatThrownBy(() -> sandbox.compile(source)).isInstanceOf(ScriptRejected.class);
    }

    @Test
    void endlessLoopsAreStopped() {
        // Arrange
        ScriptSandbox.Compiled spin = sandbox.compile("while (true) { }\nreturn sql'x'");

        // Assert
        assertThatThrownBy(() -> spin.render(Map.of())).isInstanceOf(ScriptRejected.class).hasMessageContaining("longer than");
    }

    @Test
    void ordinaryGroovyStillWorks() {
        // Arrange
        String source = """
                def parts = []
                (params.tags ?: []).eachWithIndex { t, i -> parts << sql"tag${i} = ${t}" }
                def q = sql"select 1"
                if (parts) q += sql" where "
                parts.each { q += it }
                return q
                """;

        // Act
        BoundSql b = render(source, Map.of("tags", List.of("a", "b")));

        // Assert
        assertThat(b.sql()).isEqualTo("select 1 where tag? = ?tag? = ?");
        assertThat(b.parameters()).containsExactly(0, "a", 1, "b");
    }
}
