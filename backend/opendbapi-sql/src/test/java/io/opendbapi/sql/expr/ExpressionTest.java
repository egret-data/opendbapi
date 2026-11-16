package io.opendbapi.sql.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.sql.template.TemplateException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ExpressionTest {

    private static Object eval(String source, Map<String, Object> vars) {
        return Expression.parse(source).evaluate(vars::get);
    }

    private static boolean test(String source, Map<String, Object> vars) {
        return Expression.parse(source).test(vars::get);
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void myBatisNullAndEmptyGuard() {
        // Arrange
        String guard = "name != null and name != ''";

        // Act + Assert
        assertThat(test(guard, vars("name", "an"))).isTrue();
        assertThat(test(guard, vars("name", ""))).isFalse();
        assertThat(test(guard, vars())).as("missing is null").isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "age > 18 && age <= 65            ; true",
            "age gt 18 and age lte 65         ; true",
            "age == 30                        ; true",
            "age eq 30.0                      ; true",
            "age neq 30                       ; false",
            "!(age < 18) || missing           ; true",
            "not (age >= 31)                  ; true",
            "status == '1'                    ; true",
            "status == 1                      ; true",
            "ids.size() > 1 and !ids.isEmpty() ; true",
            "name.length() == 2               ; true",
            "tags.isEmpty()                   ; true",
            "user.role == 'admin'             ; true",
            "ids[1] == 20                     ; true",
            "missing == null                  ; true",
            "missing > 1                      ; false",
            "'b' > 'a'                        ; true",
            "flag                             ; true",
            "count                            ; false",
    })
    void evaluatesTestExpressions(String source, boolean expected) {
        // Arrange
        Map<String, Object> vars = vars(
                "age", 30L, "status", "1", "ids", List.of(10, 20), "name", "an", "tags", List.of(),
                "user", Map.of("role", "admin"), "flag", true, "count", 0);

        // Act + Assert
        assertThat(test(source, vars)).as(source).isEqualTo(expected);
    }

    @Test
    void bindBuildsLikePatternsAndTreatsNullAsEmpty() {
        // Act + Assert
        assertThat(eval("'%' + keyword + '%'", vars("keyword", "acme"))).isEqualTo("%acme%");
        assertThat(eval("'%' + keyword + '%'", vars())).isEqualTo("%%");
    }

    @Test
    void arithmeticIsDecimalExact() {
        // Act + Assert
        assertThat(eval("(page - 1) * size", vars("page", 3, "size", 20))).isEqualTo(new BigDecimal("40"));
        assertThat(eval("0.1 + 0.2", vars())).isEqualTo(new BigDecimal("0.3"));
        assertThat(eval("7 % 3", vars())).isEqualTo(new BigDecimal("1"));
    }

    @Test
    void stringsSupportBothQuotesAndEscapes() {
        // Act + Assert
        assertThat(eval("'it''s'", vars())).isEqualTo("it's");
        assertThat(eval("\"say \\\"hi\\\"\"", vars())).isEqualTo("say \"hi\"");
    }

    @Test
    void onlyWhitelistedMethodsCanBeCalled() {
        // Act + Assert: the classic escape hatch is not even parseable
        assertThatThrownBy(() -> Expression.parse("name.getClass().forName('java.lang.Runtime')"))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("Only size(), length() and isEmpty()");
        assertThatThrownBy(() -> Expression.parse("T(java.lang.Runtime)"))
                .isInstanceOf(TemplateException.class);
        assertThatThrownBy(() -> Expression.parse("new java.io.File('/')"))
                .isInstanceOf(TemplateException.class);
    }

    @Test
    void propertyAccessOnlyReadsMapsAndLists() {
        // Arrange: a non-map object must not expose its getters
        Map<String, Object> vars = vars("s", "secret");

        // Act + Assert
        assertThat(eval("s.bytes", vars)).isNull();
        assertThat(eval("s.class", vars)).isNull();
    }

    @Test
    void syntaxErrorsSayWhere() {
        // Act + Assert
        assertThatThrownBy(() -> Expression.parse("age > "))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("column 7");
        assertThatThrownBy(() -> Expression.parse("age >> 1"))
                .isInstanceOf(TemplateException.class);
    }

    @Test
    void divisionByZeroIsAnErrorNotACrash() {
        // Act + Assert
        assertThatThrownBy(() -> eval("1 / zero", vars("zero", 0)))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("Division by zero");
    }

    @Test
    void reportsRootVariablesItReads() {
        // Act + Assert
        assertThat(Expression.parse("a != null and b.c > 1 or ids.size() > 0 and 'x' == d").rootVariables())
                .containsExactlyInAnyOrder("a", "b", "ids", "d");
    }
}
