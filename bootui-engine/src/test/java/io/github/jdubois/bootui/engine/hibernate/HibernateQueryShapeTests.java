package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HibernateQueryShapeTests {

    @Test
    void provesOnlyTheNarrowStaticBulkSubsetIrrelevantToMultipleCollectionFetching() {
        for (String query : List.of(
                "update QueryOrder o set o.status = :status",
                " UPDATE QueryOrder AS o SET o.status = ?1 WHERE o.id = ?2 ",
                "uPdAtE QueryOrder O sEt O.status = :status WhErE O.id = :id",
                "delete from QueryOrder o",
                "delete from QueryOrder as o where o.id = :id",
                "DeLeTe FrOm QueryOrder O wHeRe O.id = ?1",
                "/* join fetch o.items */ delete from QueryOrder o /* no fetching */",
                "update QueryOrder o set o.status = 1 where o.id = 2")) {
            assertThat(irrelevant(query, verifiedBulkEvidence())).as(query).isTrue();
        }
    }

    @Test
    void bulkScopeProofRequiresExactAssignmentAliasCase() {
        for (String query :
                List.of("update QueryOrder o set O.status = :status", "update QueryOrder O set o.status = :status")) {
            assertThat(irrelevant(query, verifiedBulkEvidence())).as(query).isFalse();
        }
    }

    @Test
    void bulkScopeProofRequiresExactPredicateAliasCase() {
        for (String query : List.of(
                "update QueryOrder o set o.status = :status where O.id = :id",
                "delete from QueryOrder o where O.id = :id",
                "delete from QueryOrder O where o.id = :id")) {
            assertThat(irrelevant(query, verifiedBulkEvidence())).as(query).isFalse();
        }
    }

    @Test
    void bulkScopeProofRejectsRawDoubleMinusInsteadOfStrippingIt() {
        for (String query : List.of(
                "update QueryOrder o set o.id = 1--1",
                "delete from QueryOrder o -- trailing text",
                "delete from QueryOrder o /* -- */")) {
            assertThat(irrelevant(query, verifiedBulkEvidence())).as(query).isFalse();
        }
    }

    @Test
    void anEmptyFetchPathListDoesNotProveMalformedDynamicOrUnsupportedQueriesIrrelevant() {
        for (String query : List.of(
                "",
                "update",
                "update QueryOrder o set",
                "delete from QueryOrder where",
                "delete from MissingEntity o",
                "delete from QueryOrder o where o.missing = :id",
                "update QueryOrder o set o.missing = :status",
                "update QueryOrder o set o.items = :items",
                "delete from QueryOrder o where o.items = :items",
                "update QueryOrder o set o.scratch = :value",
                "delete from QueryOrder o where o.scratch = :value",
                "update QueryOrder o set o.labels = :values",
                "delete from QueryOrder o where o.labels = :values",
                "update QueryOrder o set o.status = :status where",
                "update QueryOrder o set o.status = 1 'trailing literal'",
                "update QueryOrder o set o.status = 'unclosed",
                "delete from QueryOrder o /* unclosed",
                "delete from QueryOrder o where (o.id = :id",
                "delete from QueryOrder o where o.id = :id)",
                "delete from QueryOrder o where o.id = :#{#id}",
                "delete from #{#entityName} o",
                "delete from ${entity} o",
                "update QueryOrder o set o.status = :status /* #{#fragment} */",
                "delete from QueryOrder o /* ${x} */",
                "delete from QueryOrder o /* \"quoted\" */",
                "select o from QueryOrder o",
                "select o from QueryOrder o where exists (select i from QueryOrder i)",
                "select o from QueryOrder o union select p from QueryOrder p",
                "select o from QueryOrder o, QueryOrder p",
                "delete from QueryOrder o " + " ".repeat(16_384))) {
            assertThat(irrelevant(query, verifiedBulkEvidence())).as(query).isFalse();
        }
        assertThat(irrelevant(null, verifiedBulkEvidence())).isFalse();
    }

    @Test
    void bulkScopeProofRequiresVerifiedDeclaredUnrewrittenQueryProvenance() {
        for (HibernateQueryEvidence evidence : List.of(
                HibernateQueryEvidence.unknown(),
                new HibernateQueryEvidence(false, true, false, false, false, int.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, false, false, false, false, int.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, true, false, int.class, false, null, null, List.of()),
                new HibernateQueryEvidence(true, true, false, false, true, int.class, false, null, null, List.of()))) {
            assertThat(irrelevant("delete from QueryOrder o", evidence))
                    .as(evidence.toString())
                    .isFalse();
        }
    }

    @Test
    void bulkScopeProofDoesNotGuessAnUnobservedOrAmbiguousMappedTarget() {
        HibernateEntityModel entity = HibernateEntityModel.fromClass(QueryOrder.class);
        assertThat(irrelevant("delete from QueryOrder o", verifiedBulkEvidence(), List.of()))
                .isFalse();
        assertThat(irrelevant("delete from QueryOrder o", verifiedBulkEvidence(), List.of(entity, entity)))
                .isFalse();
    }

    private static boolean irrelevant(String query, HibernateQueryEvidence evidence) {
        return irrelevant(query, evidence, List.of(HibernateEntityModel.fromClass(QueryOrder.class)));
    }

    private static boolean irrelevant(
            String query, HibernateQueryEvidence evidence, List<HibernateEntityModel> entities) {
        HibernateContext context = new HibernateContext(entities, List.of(), key -> null, List.of());
        HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                "QueryOrderRepository",
                "bulk",
                QueryOrder.class,
                int.class,
                query,
                false,
                null,
                false,
                true,
                false,
                false,
                List.of(),
                evidence);
        return HibernateQueryShape.irrelevantToMultipleCollectionFetch(context, method);
    }

    private static HibernateQueryEvidence verifiedBulkEvidence() {
        return new HibernateQueryEvidence(true, true, false, false, false, int.class, false, null, null, List.of());
    }

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

        @jakarta.persistence.Transient
        String scratch;

        @jakarta.persistence.ElementCollection
        List<String> labels;

        @jakarta.persistence.OneToMany
        List<QueryOrder> items;
    }
}
