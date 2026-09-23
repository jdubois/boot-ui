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
    void omittedReferencedColumnDefaultsToTheTargetsSinglePrimaryKeyColumn() {
        TableModel target = orders(List.of("id"));
        SchemaSnapshot schema =
                schema("ds", Dialect.GENERIC, List.of(orderItems(List.of(orderForeignKey("id"))), target));
        ForeignKeyMatching.Assessment matched = ForeignKeyMatching.assess(
                context(schema), schema, schema.tables().get(0), defaultedOrder());
        assertThat(matched.status()).isEqualTo(ForeignKeyMatching.Status.MATCHED);
        assertThat(matched.foreignKey().name()).isEqualTo("fk_order_items_order_id");

        schema = schema("ds", Dialect.GENERIC, List.of(orderItems(List.of()), target));
        assertThat(ForeignKeyMatching.assess(
                                context(schema), schema, schema.tables().get(0), defaultedOrder())
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.NOT_FOUND);
    }

    @Test
    void omittedReferencedColumnStaysUnknownWithoutASingleObservedPrimaryKey() {
        TableModel unreadPrimaryKey =
                orders(List.of("id")).withMetadata(new TableMetadata(true, false, true, true, false, List.of()));
        for (TableModel target : List.of(orders(List.of()), orders(List.of("id", "code")), unreadPrimaryKey)) {
            SchemaSnapshot schema =
                    schema("ds", Dialect.GENERIC, List.of(orderItems(List.of(orderForeignKey("id"))), target));
            assertThat(ForeignKeyMatching.assess(
                                    context(schema), schema, schema.tables().get(0), defaultedOrder())
                            .status())
                    .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
        }
    }

    @Test
    void omittedReferencedColumnPairedWithANonPrimaryKeyColumnIsUnknownNotMissing() {
        SchemaSnapshot schema = schema(
                "ds", Dialect.GENERIC, List.of(orderItems(List.of(orderForeignKey("code"))), orders(List.of("id"))));
        ForeignKeyMatching.Assessment assessment = ForeignKeyMatching.assess(
                context(schema), schema, schema.tables().get(0), defaultedOrder());
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
        MappedForeignKeyFacts composite = new MappedForeignKeyFacts(
                "association", List.of("A", "B"), Arrays.asList("X", null), null, true, "PARENT", "PUBLIC", "APP");
        assertThat(ForeignKeyMatching.assess(context(schema), schema, child, composite)
                        .status())
                .isEqualTo(ForeignKeyMatching.Status.UNKNOWN);
    }

    private static MappedForeignKeyFacts defaultedOrder() {
        return new MappedForeignKeyFacts(
                "OrderItemEntity#order",
                List.of("order_id"),
                Arrays.asList((String) null),
                null,
                true,
                "orders",
                null,
                null);
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
