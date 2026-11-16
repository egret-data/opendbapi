package io.opendbapi.sql.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SqlTemplateTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static BoundSql render(String template, Map<String, Object> params) {
        return SqlTemplate.compile(template).render(params);
    }

    @Nested
    class Placeholders {

        @Test
        void hashBraceBecomesABindParameter() {
            // Act
            BoundSql b = render("select * from users where id = #{id} and name = #{name}", params("id", 42, "name", "x'; drop"));

            // Assert
            assertThat(b.sql()).isEqualTo("select * from users where id = ? and name = ?");
            assertThat(b.parameters()).containsExactly(42, "x'; drop");
        }

        @Test
        void missingParameterBindsNull() {
            // Act + Assert
            assertThat(render("select #{nope}", params()).parameters()).containsExactly((Object) null);
        }

        @Test
        void pathsAndMyBatisTypeHintsAreAccepted() {
            // Act
            BoundSql b = render("select #{filter.from, jdbcType=DATE}, #{ids[1]}",
                    params("filter", Map.of("from", "2026-01-01"), "ids", List.of(1, 2)));

            // Assert
            assertThat(b.sql()).isEqualTo("select ?, ?");
            assertThat(b.parameters()).containsExactly("2026-01-01", 2);
        }

        @Test
        void collectionsExpandToOnePlaceholderPerElement() {
            // Act
            BoundSql many = render("id in (#{ids})", params("ids", List.of(1, 2, 3)));
            BoundSql array = render("id in (#{ids})", params("ids", new String[] {"a", "b"}));
            BoundSql none = render("id in (#{ids})", params("ids", List.of()));

            // Assert
            assertThat(many.sql()).isEqualTo("id in (?, ?, ?)");
            assertThat(many.parameters()).containsExactly(1, 2, 3);
            assertThat(array.parameters()).containsExactly("a", "b");
            assertThat(none.sql()).as("empty IN matches nothing, still valid SQL").isEqualTo("id in (NULL)");
            assertThat(none.parameters()).isEmpty();
        }

        @Test
        void rawDollarSubstitutionIsRefused() {
            // Act + Assert
            assertThatThrownBy(() -> SqlTemplate.compile("select * from t order by ${sort}"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("${...} is not supported")
                    .hasMessageContaining("<orderBy");
        }
    }

    @Nested
    class Tags {

        @Test
        void whereDropsLeadingAndOrAndDisappearsWhenEmpty() {
            // Arrange
            String t = """
                    select * from users
                    <where>
                      <if test="name != null">and name = #{name}</if>
                      <if test="status != null">or status = #{status}</if>
                    </where>""";

            // Act + Assert
            assertThat(render(t, params("name", "an")).sql()).isEqualTo("select * from users\nWHERE name = ?");
            assertThat(render(t, params("status", 1)).sql()).isEqualTo("select * from users\nWHERE status = ?");
            assertThat(render(t, params()).sql()).isEqualTo("select * from users");
        }

        @Test
        void setDropsTheTrailingComma() {
            // Arrange
            String t = """
                    update users <set>
                      <if test="name != null">name = #{name},</if>
                      <if test="email != null">email = #{email},</if>
                    </set> where id = #{id}""";

            // Act
            BoundSql b = render(t, params("name", "an", "id", 7));

            // Assert
            assertThat(b.sql()).isEqualTo("update users SET name = ? where id = ?");
            assertThat(b.parameters()).containsExactly("an", 7);
        }

        @Test
        void trimSupportsPipeSeparatedOverrides() {
            // Arrange
            String t = """
                    select * from t <trim prefix="WHERE (" suffix=")" prefixOverrides="AND |OR " suffixOverrides=",">
                    OR a = 1,</trim>""";

            // Act + Assert
            assertThat(render(t, params()).sql()).isEqualTo("select * from t WHERE (a = 1)");
        }

        @Test
        void foreachJoinsItemsWithIndexAndNestedFields() {
            // Arrange
            String t = """
                    insert into t (pos, name) values
                    <foreach collection="rows" item="r" index="i" separator=",">(#{i}, #{r.name})</foreach>""";

            // Act
            BoundSql b = render(t, params("rows", List.of(Map.of("name", "a"), Map.of("name", "b"))));

            // Assert
            assertThat(b.sql()).isEqualTo("insert into t (pos, name) values\n(?, ?),(?, ?)");
            assertThat(b.parameters()).containsExactly(0, "a", 1, "b");
        }

        @Test
        void foreachWithOpenCloseAndConditions() {
            // Arrange
            String t = """
                    <where><if test="ids != null and ids.size() > 0">id in
                    <foreach collection="ids" item="id" open="(" close=")" separator=", ">#{id}</foreach></if></where>""";

            // Act + Assert
            assertThat(render(t, params("ids", List.of(5, 6))).sql()).isEqualTo("WHERE id in\n(?, ?)");
            assertThat(render(t, params("ids", List.of())).sql()).isEmpty();
        }

        @Test
        void foreachItemDoesNotLeakOutOfTheLoop() {
            // Arrange: "id" outside the loop is the request parameter, not the last item
            String t = "<foreach collection=\"ids\" item=\"id\" separator=\",\">#{id}</foreach> / #{id}";

            // Act
            BoundSql b = render(t, params("ids", List.of(1, 2), "id", 99));

            // Assert
            assertThat(b.parameters()).containsExactly(1, 2, 99);
        }

        @Test
        void foreachOverANonCollectionIsAClearError() {
            // Act + Assert
            assertThatThrownBy(() -> render("<foreach collection=\"ids\" item=\"x\">#{x}</foreach>", params("ids", "1,2")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("ids")
                    .hasMessageContaining("list");
        }

        @Test
        void chooseTakesTheFirstMatchingWhenElseOtherwise() {
            // Arrange
            String t = """
                    select * from t where <choose>
                      <when test="kind == 'a'">a = 1</when>
                      <when test="kind != null">b = #{kind}</when>
                      <otherwise>c = 1</otherwise>
                    </choose>""";

            // Act + Assert
            assertThat(render(t, params("kind", "a")).sql()).isEqualTo("select * from t where a = 1");
            assertThat(render(t, params("kind", "z")).sql()).isEqualTo("select * from t where b = ?");
            assertThat(render(t, params()).sql()).isEqualTo("select * from t where c = 1");
        }

        @Test
        void bindDefinesAVariableForLaterUse() {
            // Arrange
            String t = "<bind name=\"kw\" value=\"'%' + keyword + '%'\"/>select * from t where name like #{kw}";

            // Act
            BoundSql b = render(t, params("keyword", "acme"));

            // Assert
            assertThat(b.sql()).isEqualTo("select * from t where name like ?");
            assertThat(b.parameters()).containsExactly("%acme%");
        }
    }

    @Nested
    class OrderBy {

        private final String t = "select * from users <orderBy param=\"sort\" allow=\"created_at, name, u.id\" default=\"created_at desc\"/>";

        @Test
        void acceptsAllowedColumnsWithDirection() {
            // Act + Assert
            assertThat(render(t, params("sort", "NAME asc, created_at")).sql())
                    .isEqualTo("select * from users ORDER BY name ASC, created_at");
            assertThat(render(t, params("sort", List.of("u.id desc"))).sql())
                    .isEqualTo("select * from users ORDER BY u.id DESC");
        }

        @Test
        void fallsBackToTheDefault() {
            // Act + Assert
            assertThat(render(t, params()).sql()).isEqualTo("select * from users ORDER BY created_at DESC");
        }

        @Test
        void refusesAnythingNotInTheWhitelist() {
            // Act + Assert
            assertThatThrownBy(() -> render(t, params("sort", "password")))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("'password' is not sortable")
                    .hasMessageContaining("created_at, name, u.id");
            assertThatThrownBy(() -> render(t, params("sort", "name; drop table users")))
                    .isInstanceOf(TemplateException.class);
            assertThatThrownBy(() -> render(t, params("sort", "(select 1)")))
                    .isInstanceOf(TemplateException.class);
        }

        @Test
        void noDefaultMeansNoOrderBy() {
            // Act + Assert
            assertThat(render("select 1 <orderBy param=\"sort\" allow=\"a\"/>", params()).sql()).isEqualTo("select 1");
        }
    }

    @Nested
    class Syntax {

        @Test
        void comparisonOperatorsNeedNoEscaping() {
            // Act
            BoundSql b = render("""
                    select * from t where a < #{x} and b <> 1 and c >= 2 and d <= 3 and e > 0 and f && g
                    <if test="x < 10 and x >= 1">and h < 5</if>""", params("x", 3));

            // Assert
            assertThat(b.sql()).isEqualTo(
                    "select * from t where a < ? and b <> 1 and c >= 2 and d <= 3 and e > 0 and f && g\nand h < 5");
        }

        @Test
        void misspeltTagsAreErrorsButTightComparisonsAreSql() {
            // Act + Assert
            assertThatThrownBy(() -> SqlTemplate.compile("select 1\n<iff test=\"a\">x</iff>"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("Unknown tag <iff> at line 2");
            assertThatThrownBy(() -> SqlTemplate.compile("select 1 <wherre>a = 1</wherre>"))
                    .hasMessageContaining("Unknown tag <wherre>");
            assertThat(render("select * from t where a<b and c<>d and e->>'k' = '1' and f @> g", params()).sql())
                    .isEqualTo("select * from t where a<b and c<>d and e->>'k' = '1' and f @> g");
        }

        @Test
        void cdataAndEntitiesStillWork() {
            // Act + Assert
            assertThat(render("select 1 where a <![CDATA[ < ]]> 2 and b &lt; 3", params()).sql())
                    .isEqualTo("select 1 where a  <  2 and b < 3");
        }

        @Test
        void doctypeAndEntitiesAreRefused() {
            // Act + Assert
            assertThatThrownBy(() -> SqlTemplate.compile("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>select &e;"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("DOCTYPE");
        }

        @Test
        void unknownTagsAndMissingAttributesSayWhichLine() {
            // Act + Assert
            assertThatThrownBy(() -> SqlTemplate.compile("select 1\n<iff test=\"a\">x</iff>"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("line 2");
            assertThatThrownBy(() -> SqlTemplate.compile("select 1\n\n<if>x</if>"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("<if> needs a test attribute")
                    .hasMessageContaining("line 3");
            assertThatThrownBy(() -> SqlTemplate.compile("<where><if test=\"a\">x</where>"))
                    .isInstanceOf(TemplateException.class);
        }

        @Test
        void badExpressionsFailAtCompileTimeNotPerRequest() {
            // Act + Assert
            assertThatThrownBy(() -> SqlTemplate.compile("<if test=\"a.getClass()\">x</if>"))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("<if test");
        }

        @Test
        void sqlStringLiteralsKeepTheirSpacing() {
            // Act + Assert
            assertThat(render("select '  two  spaces  ' as s", params()).sql()).isEqualTo("select '  two  spaces  ' as s");
        }
    }

    @Test
    void reportsTheParametersATemplateReads() {
        // Arrange
        String t = """
                <bind name="kw" value="'%' + keyword + '%'"/>
                select * from t
                <where>
                  <if test="status != null">status = #{status}</if>
                  <if test="kw != null">and name like #{kw}</if>
                  <foreach collection="ids" item="id" open="and id in (" close=")" separator=",">#{id}</foreach>
                  and owner = #{owner.id}
                </where>
                <orderBy param="sort" allow="name"/>""";

        // Act + Assert
        assertThat(SqlTemplate.compile(t).parameterNames())
                .containsExactlyInAnyOrder("keyword", "status", "ids", "owner", "sort");
    }

    @Test
    void aCompiledTemplateIsReusable() {
        // Arrange
        SqlTemplate t = SqlTemplate.compile("select #{a}<if test=\"b\">, #{b}</if>");

        // Act + Assert
        assertThat(t.render(params("a", 1, "b", 2)).parameters()).containsExactly(1, 2);
        assertThat(t.render(params("a", 3)).parameters()).containsExactly(3);
    }
}
