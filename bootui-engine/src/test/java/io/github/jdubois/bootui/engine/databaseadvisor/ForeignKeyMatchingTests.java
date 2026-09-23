package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.column;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForeignKeyMatchingTests {

    @Test
    void resolvesFoldedDeclarationsAndExactReorderedPairsWithKnownEnforcement() {
        TableModel child = child(List.of(physical(true)));
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent()));
        ForeignKeyMatching.Assessment assessment = ForeignKeyMatching.assess(context(schema), schema, child, mapped());
        assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.MATCHED);
        assertThat(assessment.foreignKey().enforced()).isTrue();
    }

    @Test
    void matchingUnknownOrDisabledEnforcementIsNotMisreportedAsMissing() {
        for (ForeignKeyModel foreignKey : List.of(physical(null), physical(false))) {
            TableModel child = child(List.of(foreignKey));
            SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent()));
            ForeignKeyMatching.Assessment assessment =
                    ForeignKeyMatching.assess(context(schema), schema, child, mapped());
            assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
            assertThat(assessment.foreignKey()).isSameAs(foreignKey);
        }
    }

    @Test
    void absenceRequiresCompleteResolvedTargetAndForeignKeyInventory() {
        TableModel child = child(List.of());
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent()));
        assertThat(ForeignKeyMatching.assess(context(schema), schema, child, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.NOT_FOUND);
        TableModel incomplete = child.withMetadata(new TableMetadata(true, true, false, true, false, List.of()));
        schema = schema("ds", Dialect.GENERIC, List.of(incomplete, parent()));
        assertThat(ForeignKeyMatching.assess(context(schema), schema, incomplete, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
    }

    @Test
    void failedDatasourceOrMultipleTargetsPreventsAttribution() {
        TableModel child = child(List.of(physical(true)));
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent()));
        DatabaseAdvisorContext failed = context(List.of(schema, SchemaSnapshot.failed("other", "unread")));
        assertThat(ForeignKeyMatching.assess(failed, schema, child, mapped()).status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        SchemaSnapshot duplicate = schema("other", Dialect.GENERIC, List.of(parent()));
        assertThat(ForeignKeyMatching.assess(context(List.of(schema, duplicate)), schema, child, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        SchemaSnapshot unrelated = schema(
                "other", Dialect.GENERIC, List.of(table("unrelated", List.of(), List.of(), List.of(), List.of())));
        assertThat(ForeignKeyMatching.assess(context(List.of(schema, unrelated)), schema, child, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
    }

    @Test
    void unqualifiedTargetAmbiguityAndViewsAreUnknown() {
        TableModel child = child(List.of());
        TableModel otherParent =
                TableModel.of("app", "other", "parent", parent().columns(), List.of(), List.of(), List.of());
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent(), otherParent));
        MappedForeignKeyFacts unqualified = new MappedForeignKeyFacts(
                "association", List.of("a", "b"), List.of("x", "y"), null, true, "parent", null, null);
        assertThat(ForeignKeyMatching.assess(context(schema), schema, child, unqualified)
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        TableModel view = new TableModel(
                "app",
                "public",
                "parent",
                "VIEW",
                parent().columns(),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                TableMetadata.COMPLETE);
        schema = schema("ds", Dialect.GENERIC, List.of(child, view));
        assertThat(ForeignKeyMatching.assess(context(schema), schema, child, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
    }

    @Test
    void incompleteQualifiedPhysicalTargetCannotBecomeAnAbsenceConclusion() {
        ForeignKeyModel incomplete = new ForeignKeyModel(
                "fk",
                List.of("a", "b"),
                null,
                null,
                "parent",
                List.of("x", "y"),
                0,
                0,
                DatabaseMetaData.importedKeyNotDeferrable,
                true,
                true,
                null);
        TableModel child = child(List.of(incomplete));
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, parent()));
        assertThat(ForeignKeyMatching.assess(context(schema), schema, child, mapped())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
    }

    @Test
    void canonicalPhysicalBooleanMatchingNeverTreatsNullQualifiersAsWildcards() {
        TableModel child = child(List.of(physical(true)));
        MappedForeignKeyFacts exact = new MappedForeignKeyFacts(
                "association", List.of("a", "b"), List.of("x", "y"), null, true, "parent", "public", "app");
        MappedForeignKeyFacts missingCatalog = new MappedForeignKeyFacts(
                "association", List.of("a", "b"), List.of("x", "y"), null, true, "parent", "public", null);
        assertThat(ForeignKeyMatching.hasMatchingPhysicalForeignKey(child, exact))
                .isTrue();
        assertThat(ForeignKeyMatching.hasMatchingPhysicalForeignKey(child, missingCatalog))
                .isFalse();
    }

    @Test
    void omittedReferencedColumnDefaultsToTheTargetsCorroboratedSinglePrimaryKeyColumn() {
        TableModel target = orders(List.of("id"));
        ForeignKeyMatching.Assessment matched = assessDefaulted(List.of(orderForeignKey("id")), target, "ID");
        assertThat(matched.status()).isEqualTo(ForeignKeyMatching.Status.MATCHED);
        assertThat(matched.foreignKey().name()).isEqualTo("fk_order_items_order_id");

        assertThat(assessDefaulted(List.of(), target, "id").status()).isEqualTo(ForeignKeyMatching.Status.NOT_FOUND);
    }

    @Test
    void omittedReferencedColumnWithAnUnenforcedMatchIsUnknownWithTheObservedConstraint() {
        ForeignKeyModel unenforced = orderForeignKey("id").withEnforcement(false, false, "SIMPLE");
        ForeignKeyMatching.Assessment assessment = assessDefaulted(List.of(unenforced), orders(List.of("id")), "id");
        assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        assertThat(assessment.foreignKey()).isSameAs(unenforced);
    }

    @Test
    void omittedReferencedColumnStaysUnknownWithoutASingleObservedPrimaryKey() {
        TableModel unreadPrimaryKey =
                orders(List.of("id")).withMetadata(new TableMetadata(true, false, true, true, false, List.of()));
        for (TableModel target : List.of(orders(List.of()), orders(List.of("id", "code")), unreadPrimaryKey)) {
            ForeignKeyMatching.Assessment assessment = assessDefaulted(List.of(orderForeignKey("id")), target, "id");
            assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
            assertThat(assessment.reason()).contains("primary key is not a single observed column");
        }
    }

    @Test
    void omittedReferencedColumnStaysUnknownWhenTheMappedIdentifierIsNotThePhysicalPrimaryKey() {
        // A mapped @Id on business_id while the physical primary key is id: Hibernate joins to business_id,
        // so a constraint referencing id must not be attributed to this association.
        TableModel target = table(
                "orders",
                List.of(column("id", "uuid", Types.OTHER), column("business_id", "uuid", Types.OTHER)),
                List.of("id"),
                List.of(),
                List.of());
        for (String identifier : Arrays.asList(null, "business_id", "missing")) {
            ForeignKeyMatching.Assessment assessment =
                    assessDefaulted(List.of(orderForeignKey("id")), target, identifier);
            assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
            assertThat(assessment.reason()).contains("identifier column is not established");
        }
    }

    @Test
    void omittedReferencedColumnPairedWithANonPrimaryKeyColumnIsUnknownNotMissing() {
        ForeignKeyMatching.Assessment assessment =
                assessDefaulted(List.of(orderForeignKey("code")), orders(List.of("id")), "id");
        assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        assertThat(assessment.reason()).contains("non-primary-key");
    }

    @Test
    void compositeDeclarationWithAnOmittedReferencedColumnIsUnknown() {
        TableModel child = child(List.of(physical(true)));
        TableModel target = table(
                "parent",
                List.of(column("x", "int4", Types.INTEGER), column("y", "int4", Types.INTEGER)),
                List.of("x", "y"),
                List.of(),
                List.of());
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(child, target));
        for (List<String> referenced : List.of(Arrays.asList("X", null), Arrays.<String>asList(null, null))) {
            MappedForeignKeyFacts composite = new MappedForeignKeyFacts(
                    "association", List.of("A", "B"), referenced, null, true, "PARENT", "PUBLIC", "APP", "x");
            ForeignKeyMatching.Assessment assessment =
                    ForeignKeyMatching.assess(context(schema), schema, child, composite);
            assertThat(assessment.status()).isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
            assertThat(assessment.reason()).contains("composite declaration leaves a referenced column unspecified");
        }
    }

    private static ForeignKeyMatching.Assessment assessDefaulted(
            List<ForeignKeyModel> foreignKeys, TableModel target, String identifier) {
        TableModel source = orderItems(foreignKeys);
        SchemaSnapshot schema = schema("ds", Dialect.GENERIC, List.of(source, target));
        MappedForeignKeyFacts mapped = new MappedForeignKeyFacts(
                "OrderItemEntity#order",
                List.of("ORDER_ID"),
                Arrays.asList((String) null),
                null,
                true,
                "orders",
                null,
                null,
                identifier);
        return ForeignKeyMatching.assess(context(schema), schema, source, mapped);
    }

    private static ForeignKeyModel orderForeignKey(String parentColumn) {
        return new ForeignKeyModel(
                "fk_order_items_order_id",
                List.of("order_id"),
                "app",
                "public",
                "orders",
                List.of(parentColumn),
                0,
                0,
                DatabaseMetaData.importedKeyNotDeferrable,
                true,
                true,
                null);
    }

    private static TableModel orderItems(List<ForeignKeyModel> foreignKeys) {
        return table(
                "order_items",
                List.of(column("id", "uuid", Types.OTHER), column("order_id", "uuid", Types.OTHER)),
                List.of("id"),
                foreignKeys,
                List.of());
    }

    private static TableModel orders(List<String> primaryKey) {
        return table(
                "orders",
                List.of(column("id", "uuid", Types.OTHER), column("code", "varchar", Types.VARCHAR)),
                primaryKey,
                List.of(),
                List.of());
    }

    private static MappedForeignKeyFacts mapped() {
        return new MappedForeignKeyFacts(
                "association", List.of("A", "B"), List.of("X", "Y"), null, true, "PARENT", "PUBLIC", "APP");
    }

    private static ForeignKeyModel physical(Boolean enforced) {
        return new ForeignKeyModel(
                "fk",
                List.of("b", "a"),
                "app",
                "public",
                "parent",
                List.of("y", "x"),
                0,
                0,
                DatabaseMetaData.importedKeyNotDeferrable,
                enforced,
                true,
                null);
    }

    private static TableModel child(List<ForeignKeyModel> foreignKeys) {
        return table(
                "child",
                List.of(column("a", "int4", Types.INTEGER), column("b", "int4", Types.INTEGER)),
                List.of(),
                foreignKeys,
                List.of());
    }

    private static TableModel parent() {
        return table(
                "parent",
                List.of(column("x", "int4", Types.INTEGER), column("y", "int4", Types.INTEGER)),
                List.of(),
                List.of(),
                List.of());
    }
}
