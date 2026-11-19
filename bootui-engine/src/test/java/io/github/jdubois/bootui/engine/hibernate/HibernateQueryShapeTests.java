package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HibernateQueryShapeTests {

    @Test
    void entitySelectionsOutsideThePilotStayUnknownWhenTheyFetchACollection() {
        for (String selection : List.of("o as ord", "(o)")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(
                            context,
                            filteredMethod("select " + selection
                                    + " from QueryOrder o join fetch o.items i where i.status = :status")))
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(selection).isTrue();
        }
    }

    @Test
    void implicitEmbeddablePredicatesAreUnknownNotScalarRestrictions() {
        HibernateContext context = filteredFetchContext();
        assertThat(HibernateQueryShape.filtersFetchedCollection(
                        context,
                        filteredMethod(
                                "select o from QueryOrder o join fetch o.items i where i.implicitAddress = :address")))
                .isFalse();
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void propertyAccessCollectionAndIdentifierMetadataAreNotHiddenByBackingFields() {
        HibernateContext context = filteredFetchContext();
        assertThat(HibernateQueryShape.filtersFetchedCollection(
                        context,
                        filteredMethod(
                                "select o from PropertyOrder o join fetch o.items i where i.status = :status",
                                PropertyOrder.class)))
                .isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
        context = filteredFetchContext();
        assertThat(HibernateQueryShape.filtersFetchedCollection(
                        context,
                        filteredMethod(
                                "select o from QueryOrder o join fetch o.propertyItems i where i.id is not null")))
                .isFalse();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    @Test
    void propertyAccessTransientAndAssociationPredicatesStayUnknown() {
        for (String attribute : List.of("hidden", "owner")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(
                            context,
                            filteredMethod("select o from QueryOrder o join fetch o.propertyItems i where i."
                                    + attribute + " = :value")))
                    .as(attribute)
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(attribute).isTrue();
        }
    }

    @Test
    void conflictingFieldAndGetterMappingsStayUnknown() {
        HibernateContext context = filteredFetchContext();
        assertThat(HibernateQueryShape.filtersFetchedCollection(
                        context,
                        filteredMethod(
                                "select o from ConflictingOrder o join fetch o.items i where i.status = :status",
                                ConflictingOrder.class)))
                .isFalse();
        assertThat(context.evidence().requiredUnknown()).isTrue();
    }

    @Test
    void provenNonFetchingParameterExpressionsAndCommentOrLiteralWordsStayComplete() {
        for (String query : List.of(
                "select o from QueryOrder o where o.name = :#{#p.name}",
                "select o from QueryOrder o /* #{#fragment} ${fragment} -- join fetch o.items */",
                "select o from QueryOrder o where o.name = '#{#fragment} ${fragment} -- join fetch o.items'")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                    .as(query)
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
        }
    }

    @Test
    void opaqueDynamicNonFetchingTextIsNotBlanketWaived() {
        for (String query : List.of(
                "select o from QueryOrder o #{#fragment}",
                "select o from #{#entityName} o",
                "select o from QueryOrder o where o.name = ${value}",
                "select o from QueryOrder o where o.name = :#{#p.name} and 1 = 1",
                "select o from QueryOrder o -- join fetch o.items")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                    .as(query)
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(query).isTrue();
        }
    }

    @Test
    void commentLikeDoubleMinusTextIsNotParsedAsAnOperatorSequence() {
        assertThat(HibernateQueryShape.lexical("select o from QueryOrder o -- join fetch o.items"))
                .doesNotContain("join fetch");
        assertThat(HibernateQueryShape.lexical("select o from QueryOrder o where o.id = 1--1"))
                .contains("1--1");
    }

    @Test
    void filteredFetchRequiresMappedFetchedAliasEqualityRatherThanAnAliasMention() {
        for (String query : List.of(
                "select o from QueryOrder o join fetch o.items i where i.status = :status",
                "select distinct o from QueryOrder o left join fetch o.items as i where i.name = 'included'",
                "from QueryOrder o inner join fetch o.items i where i.id = ?1",
                "select o from QueryOrder o join fetch o.items i where o.id = :id and i.status = 'a''b'",
                "select o from QueryOrder o join fetch o.items i where i.id is not null and i.status = :status",
                "select o from QueryOrder o join fetch o.items i where i.id = 42 /* review only */",
                "SeLeCt O FrOm QueryOrder O JoIn FeTcH O.items I WhErE I.status = :status")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                    .as(query)
                    .isTrue();
            assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
        }
    }

    @Test
    void filteredFetchKeepsUnrecognizedQuotedFetchPathsUnknown() {
        HibernateContext context = filteredFetchContext();
        String query = "select o from QueryOrder o join fetch o.`items` i where i.status = :status";

        assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                .isFalse();
        assertThat(context.evidence().requiredUnknown()).isTrue();
        assertThat(context.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.QUERY_JOIN_PATH);
    }

    @Test
    void filteredFetchLeavesPositivelyIrrelevantQueriesComplete() {
        for (String query : List.of(
                "select o from QueryOrder o join fetch o.items i",
                "select o from QueryOrder o join fetch o.items",
                "select o from QueryOrder o join fetch o.items where o.status = :status",
                "select o from QueryOrder o join fetch o.items i where o.name = 'i.status = included'",
                "select o from QueryOrder o join fetch o.items i /* where i.status = :status */",
                "select o from QueryOrder o join fetch o.items i where i.id is not null",
                "select o from QueryOrder o join o.items i where i.status = :status",
                "select o from QueryOrder o join fetch o.owner i where i.status = :status",
                "select o from QueryOrder o where o.status = :status",
                "select o from QueryOrder o where o.status = 'join fetch o.`items` i'",
                "select o from QueryOrder o /* join fetch o.`items` i */",
                "update QueryOrder o set o.status = :status",
                "delete from QueryOrder o where o.id = :id")) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                    .as(query)
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(query).isFalse();
        }
    }

    @Test
    void unsupportedPredicatesAndScopesNeverBecomeProvenRestrictionsOrCompletePasses() {
        for (String query : List.of(
                "select o from QueryOrder o join fetch o.items i where i.status = i.status",
                "select o from QueryOrder o join fetch o.items i where i.id = i.id",
                "select o from QueryOrder o join fetch o.items i where i.owner = o",
                "select o from QueryOrder o join fetch o.items i where i.owner.id = o.id",
                "select o from QueryOrder o join fetch o.items i where i.id = o.id",
                "select o from QueryOrder o join fetch o.items i where i = o",
                "select o from QueryOrder o join fetch o.items i where i is not null",
                "select o from QueryOrder o join fetch o.items i where 1 = 1",
                "select o from QueryOrder o join fetch o.items i where i.id = :id or 1 = 1",
                "select o from QueryOrder o join fetch o.items i where i.id = :id and 1 = 1",
                "select o from QueryOrder o join fetch o.items i where i.id = :id and i.status = i.status",
                "select o from QueryOrder o join fetch o.items i where i.status is not null",
                "select o from QueryOrder o join fetch o.items i where i.scratch = :value",
                "select o from QueryOrder o join fetch o.items i where i.address = :address",
                "select o from QueryOrder o join fetch o.items i where i.missing = :value",
                "select o from QueryOrder o join fetch o.missing i where i.status = :status",
                "select o from QueryOrder o join fetch o.status i where i.status = :status",
                "select o from QueryOrder o join fetch o.items i where I.status = :value",
                "select o from QueryOrder o join fetch o.items i where i.status = :#{#status}",
                "select o from QueryOrder o join fetch o.items i where (i.status = :status)",
                "select o from QueryOrder o join fetch o.items i where i.id > 0",
                "select o from QueryOrder o join fetch o.items i where i.id = 1--1",
                "select o from QueryOrder o join fetch o.items i where o.id in "
                        + "(select i.id from QueryOrder i where i.status = :status)",
                "select o from QueryOrder o join fetch o.items i where i.status = 'unclosed",
                "select o from QueryOrder o join fetch o.items i /* unclosed",
                "select o from QueryOrder o join o.items i join fetch i.items c where c.status = :status",
                "select o from QueryOrder o join fetch o.items i, QueryOrder other where i.status = :status",
                "select o from QueryOrder o join fetch o.items i where i.status = :status union "
                        + "select o from QueryOrder o",
                "select o from QueryOrder o join fetch o.items i where i.status = :status" + " ".repeat(16_384))) {
            HibernateContext context = filteredFetchContext();
            assertThat(HibernateQueryShape.filtersFetchedCollection(context, filteredMethod(query)))
                    .as(query)
                    .isFalse();
            assertThat(context.evidence().requiredUnknown()).as(query).isTrue();
        }
    }

    @Test
    void filteredFetchDoesNotGuessMissingTargetMetadata() {
        HibernateContext context = new HibernateContext(
                List.of(new HibernateEntityModel(
                        QueryOrder.class.getName(),
                        QueryOrder.class,
                        List.of(new HibernateAttributeModel(
                                QueryOrder.class.getName(),
                                "items",
                                List.class,
                                List.class,
                                "ONE_TO_MANY",
                                false,
                                true,
                                List.of())))),
                List.of(),
                key -> null,
                List.of());
        assertThat(HibernateQueryShape.filtersFetchedCollection(
                        context, filteredMethod("select o from QueryOrder o join fetch o.items i where i.id = :id")))
                .isFalse();
        assertThat(context.evidence().gaps()).containsOnlyKeys(HibernateEvidenceGap.QUERY_ENTITY);
    }

    private static HibernateContext filteredFetchContext() {
        return new HibernateContext(
                List.of(
                        HibernateEntityModel.fromClass(QueryOrder.class),
                        HibernateEntityModel.fromClass(PropertyOrder.class),
                        HibernateEntityModel.fromClass(ConflictingOrder.class)),
                List.of(),
                key -> null,
                List.of());
    }

    private static HibernateRepositoryMethodModel filteredMethod(String query) {
        return filteredMethod(query, QueryOrder.class);
    }

    private static HibernateRepositoryMethodModel filteredMethod(String query, Class<?> entity) {
        return new HibernateRepositoryMethodModel(
                "QueryOrderRepository",
                "filtered",
                entity,
                List.class,
                query,
                false,
                null,
                false,
                false,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(true, true, false, false, false, entity, false, null, null, List.of()));
    }

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

        @jakarta.persistence.ManyToOne
        QueryOrder owner;

        @jakarta.persistence.Embedded
        Address address;

        Address implicitAddress;

        @jakarta.persistence.OneToMany
        List<PropertyOrder> propertyItems;
    }

    @jakarta.persistence.Entity
    static class PropertyOrder {
        private Long id;
        private List<PropertyOrder> items;
        private String status;
        private String hidden;
        private PropertyOrder owner;

        @jakarta.persistence.Id
        Long getId() {
            return id;
        }

        @jakarta.persistence.OneToMany
        List<PropertyOrder> getItems() {
            return items;
        }

        @jakarta.persistence.Basic
        String getStatus() {
            return status;
        }

        @jakarta.persistence.Transient
        String getHidden() {
            return hidden;
        }

        @jakarta.persistence.ManyToOne
        PropertyOrder getOwner() {
            return owner;
        }
    }

    @jakarta.persistence.Entity
    static class ConflictingOrder {
        @jakarta.persistence.Id
        Long id;

        @jakarta.persistence.ManyToMany
        List<ConflictingOrder> items;

        String status;

        @jakarta.persistence.OneToMany
        List<ConflictingOrder> getItems() {
            return items;
        }
    }

    @jakarta.persistence.Embeddable
    static class Address {
        String street;
    }
}
