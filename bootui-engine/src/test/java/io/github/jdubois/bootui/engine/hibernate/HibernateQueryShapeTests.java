package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HibernateQueryShapeTests {

    @Test
    void recognizesTimestampAssignments() {
        for (String expression : List.of(
                "CURRENT_TIMESTAMP",
                "current_timestamp()",
                "CuRrEnT_TiMeStAmP ( )",
                "((current_timestamp()))",
                "current_timestamp /* database time */ ()")) {
            assertThat(maintainsVersion("update Order o set o.lastModified = " + expression, "o", "lastModified"))
                    .as("timestamp assignment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void recognizesDirectParameterAssignments() {
        for (String expression : List.of(":now", ":nextVersion", ":next_version2", "?1", "?12", "((:now))", "(?2)")) {
            assertThat(maintainsVersion("update Order o set o.lastModified = " + expression, "o", "lastModified"))
                    .as("parameter assignment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void recognizesParameterizedIncrements() {
        for (String expression : List.of(
                "o.version + :delta",
                ":delta + o.version",
                "o.version + ?1",
                "?2 + o.version",
                "((o.version) + (:delta))",
                "((?1) + (version))")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("parameterized increment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void preservesLiteralIncrements() {
        for (String expression : List.of("o.version + 1", "1L + o.version", "((version) + (2l))")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("literal increment: %s", expression)
                    .isTrue();
        }
    }

    @Test
    void matchesTheVersionAssignmentInTheSetClause() {
        for (String query : List.of(
                "update Order o set lastModified = :now where o.id = :id",
                "update Order o set o . lastModified = (:now) where o.id = :id",
                "update Order o set o.status = coalesce(:status, o.status), o.lastModified = :now",
                "update Order o set o.lastModified = :now, o.status = coalesce(:status, o.status)")) {
            assertThat(maintainsVersion(query, "o", "lastModified")).as(query).isTrue();
        }
        assertThat(maintainsVersion("update Order set lastModified = current_timestamp", null, "lastModified"))
                .isTrue();
    }

    @Test
    void rejectsSelfAssignmentsAndUnsupportedExpressions() {
        for (String expression : List.of(
                "o.version",
                "((o.version))",
                "version",
                "1",
                "null",
                "coalesce(o.version, :next)",
                "some_function()",
                "current_timestamp(1)",
                "current_timestamp + 1",
                "",
                ":",
                ":next trailing",
                "?",
                "?0",
                "?1suffix",
                "(current_timestamp()",
                "current_timestamp())",
                "o.version +",
                "o.version + :",
                "o.version + ?0",
                "o.version + :delta + 1")) {
            assertThat(maintainsVersion("update Order o set o.version = " + expression, "o", "version"))
                    .as("unsupported assignment: %s", expression)
                    .isFalse();
        }
    }

    @Test
    void ignoresOtherAttributesAliasesPredicatesAndQuotedText() {
        for (String query : List.of(
                "update Order o set o.status = :now",
                "update Order o set o.lastModifiedCopy = :now",
                "update Order o set other.lastModified = :now",
                "update Order o set o.status = :status where o.lastModified = :now",
                "update Order o set o.status = 'o.lastModified = CURRENT_TIMESTAMP'",
                "update Order o set o.status = :status /* o.lastModified = :now */",
                "update Order o set o.status = :status -- o.lastModified = :now\n",
                "update Order o set o.lastModified = 'CURRENT_TIMESTAMP'",
                "update Order o set o.lastModified = ':now'")) {
            assertThat(maintainsVersion(query, "o", "lastModified")).as(query).isFalse();
        }
    }

    @Test
    void requiresQueryAndVersionAttribute() {
        assertThat(maintainsVersion(null, "o", "version")).isFalse();
        assertThat(maintainsVersion("update Order o set o.version = :next", "o", null))
                .isFalse();
        assertThat(maintainsVersion("update Order o set o.version = :next", "o", " "))
                .isFalse();
    }

    @Test
    void rootAllowsCommasOutsideFromClauseAndSingleHopJoinAliases() {
        for (String query : List.of(
                "select o.id, o.name from QueryOrder o",
                "select o from QueryOrder o where o.status in (:a, :b) and coalesce(o.name, 'fallback') = :name",
                "select o from QueryOrder o group by o.id, o.name having count(o) > 1 order by o.name desc, o.id asc",
                "select o from QueryOrder o join fetch o.items i where i.id > 0 order by o.id, i.id",
                "select o from QueryOrder o left join o.items as i where i.id in (:a, :b)")) {
            HibernateContext context = new HibernateContext(
                    List.of(HibernateEntityModel.fromClass(QueryOrder.class)), List.of(), key -> null, List.of());
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "com.example.QueryOrderRepository",
                    "findCustom",
                    QueryOrder.class,
                    List.class,
                    query,
                    false,
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of());
            assertThat(HibernateQueryShape.root(context, method, false))
                    .as(query)
                    .isNotNull()
                    .extracting(HibernateEntityModel::javaType)
                    .isEqualTo(QueryOrder.class);
            assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
        }
    }

    @Test
    void rootRejectsMultiRootFromClauseCommasAndMultiHopJoins() {
        for (String query : List.of(
                "select o from QueryOrder o, QueryOrder other where o.id = other.id",
                "select o from QueryOrder o join o.items i, QueryOrder other",
                "select o from QueryOrder o join o.items i on i.name = :where, QueryOrder other")) {
            HibernateContext context = new HibernateContext(
                    List.of(HibernateEntityModel.fromClass(QueryOrder.class)), List.of(), key -> null, List.of());
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "com.example.QueryOrderRepository",
                    "findMultiRoot",
                    QueryOrder.class,
                    List.class,
                    query,
                    false,
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of());
            assertThat(HibernateQueryShape.root(context, method, false))
                    .as(query)
                    .isNull();
            assertThat(context.evidence().gaps()).as(query).containsOnlyKeys(HibernateEvidenceGap.QUERY_SHAPE);
        }

        for (String query : List.of(
                "select o from QueryOrder o join o.items i join fetch i.children",
                "select o from QueryOrder o join fetch o.items.children c")) {
            HibernateContext context = new HibernateContext(
                    List.of(HibernateEntityModel.fromClass(QueryOrder.class)), List.of(), key -> null, List.of());
            HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                    "com.example.QueryOrderRepository",
                    "findNestedJoin",
                    QueryOrder.class,
                    List.class,
                    query,
                    false,
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of());
            assertThat(HibernateQueryShape.root(context, method, false))
                    .as(query)
                    .isNull();
            assertThat(context.evidence().gaps()).as(query).containsOnlyKeys(HibernateEvidenceGap.QUERY_JOIN_PATH);
        }
    }

    private static boolean maintainsVersion(String query, String alias, String attribute) {
        return HibernateQueryShape.maintainsVersion(HibernateQueryShape.lexical(query), alias, attribute);
    }

    @jakarta.persistence.Entity
    static class QueryOrder {
        @jakarta.persistence.Id
        Long id;

        String name;

        String status;

        @jakarta.persistence.OneToMany
        List<QueryOrder> items;
    }
}
