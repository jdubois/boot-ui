package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSecondaryTableFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSequenceGeneratorFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Convert;
import jakarta.persistence.Converts;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the mapping facts the Database Advisor cross-reference rules compare against a live
 * database catalog.
 *
 * <p>The bridge's contract is that everything it reports is <em>explicitly declared</em>: a mapping detail
 * that was not spelled out by the developer must come back as "not declared" rather than as the JPA default,
 * because a rule comparing an invented default against the physical schema produces findings nobody can act
 * on. These tests pin both halves of that contract — what is reported, and what is deliberately withheld.</p>
 */
class HibernateSchemaBridgeTests {

    private static MappedEntityFacts factsFor(Class<?> entityType) {
        List<MappedEntityFacts> mapped =
                HibernateSchemaBridge.toMappedEntities(List.of(HibernateEntityModel.fromClass(entityType)));
        assertThat(mapped).hasSize(1);
        return mapped.get(0);
    }

    private static MappedColumnFacts column(MappedEntityFacts facts, String columnName) {
        return facts.columns().stream()
                .filter(candidate -> candidate.columnName().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mapped column named " + columnName + " in " + facts));
    }

    // --- table identity -------------------------------------------------------------------------------

    @Test
    void explicitTableCatalogAndSchemaAreReported() {
        MappedEntityFacts facts = factsFor(Order.class);

        assertThat(facts.entityName()).isEqualTo(Order.class.getName());
        assertThat(facts.explicitTableName()).isEqualTo("orders");
        assertThat(facts.explicitSchema()).isEqualTo("sales");
        assertThat(facts.explicitCatalog()).isEqualTo("warehouse");
        assertThat(facts.qualifiedTableName()).isEqualTo("sales.orders");
    }

    @Test
    void anEntityRelyingOnTheNamingStrategyReportsNoTableName() {
        MappedEntityFacts facts = factsFor(ImplicitlyNamed.class);

        assertThat(facts.explicitTableName()).isNull();
        assertThat(facts.explicitSchema()).isNull();
        assertThat(facts.explicitCatalog()).isNull();
        assertThat(facts.qualifiedTableName()).isNull();
    }

    @Test
    void tableDeclaredOnAMappedSuperclassIsNotAnEntityTableDeclaration() {
        MappedEntityFacts facts = factsFor(InheritsTable.class);

        assertThat(facts.explicitTableName()).isNull();
        assertThat(facts.explicitSchema()).isNull();
    }

    @Test
    void everyEntityIsMappedInOrder() {
        List<MappedEntityFacts> mapped = HibernateSchemaBridge.toMappedEntities(
                List.of(HibernateEntityModel.fromClass(Order.class), HibernateEntityModel.fromClass(Customer.class)));

        assertThat(mapped).extracting(MappedEntityFacts::explicitTableName).containsExactly("orders", "customer");
        assertThat(HibernateSchemaBridge.toMappedEntities(List.of())).isEmpty();
    }

    // --- columns --------------------------------------------------------------------------------------

    @Test
    void onlyAttributesWithAnExplicitColumnNameAreReported() {
        MappedEntityFacts facts = factsFor(Columns.class);

        assertThat(facts.columns())
                .extracting(MappedColumnFacts::columnName)
                .containsExactlyInAnyOrder("id", "email", "short_code", "default_length", "payload", "status", "money");
    }

    @Test
    void identifierAndDeclaredNullabilityAreReported() {
        MappedEntityFacts facts = factsFor(Columns.class);

        MappedColumnFacts id = column(facts, "id");
        assertThat(id.identifier()).isTrue();
        assertThat(id.attributeDescription()).isEqualTo(Columns.class.getName() + "#id");
        assertThat(id.javaTypeSimpleName()).isEqualTo("Long");

        assertThat(column(facts, "email").nullable()).isFalse();
        assertThat(column(facts, "email").identifier()).isFalse();
        assertThat(column(facts, "short_code").nullable()).isNull();
    }

    @Test
    void theJpaDefaultLengthIsReportedAsNotDeclared() {
        MappedEntityFacts facts = factsFor(Columns.class);

        assertThat(column(facts, "short_code").declaredLength()).isEqualTo(32);
        assertThat(column(facts, "default_length").declaredLength()).isNull();
        assertThat(column(facts, "email").declaredLength()).isNull();
    }

    @Test
    void convertersEnumsAndLobsMarkTheColumnTypeAsAmbiguous() {
        MappedEntityFacts facts = factsFor(Columns.class);

        assertThat(column(facts, "email").ambiguousType()).isFalse();
        assertThat(column(facts, "payload").ambiguousType()).isTrue();
        assertThat(column(facts, "payload").lob()).isTrue();
        assertThat(column(facts, "status").ambiguousType()).isTrue();
        assertThat(column(facts, "status").lob()).isFalse();
        assertThat(column(facts, "money").ambiguousType()).isTrue();
    }

    @Test
    void defaultValuedNullabilityAndNonpositiveLengthsAreUnknownEvenWhenWrittenExplicitly() {
        MappedEntityFacts facts = factsFor(AnnotationDefaults.class);
        assertThat(column(facts, "omitted").nullable()).isNull();
        assertThat(column(facts, "explicit_true").nullable()).isNull();
        assertThat(column(facts, "required").nullable()).isFalse();
        assertThat(column(facts, "omitted").declaredLength()).isNull();
        assertThat(column(facts, "explicit_true").declaredLength()).isNull();
        assertThat(column(facts, "zero").declaredLength()).isNull();
        assertThat(column(facts, "negative").declaredLength()).isNull();
        assertThat(column(facts, "required").declaredLength()).isEqualTo(12);
    }

    @Test
    void repeatableClassAndNativeColumnMappingsAreAmbiguousForLengthComparison() {
        assertThat(column(factsFor(RepeatedConversion.class), "value").ambiguousType())
                .isTrue();
        assertThat(column(factsFor(ClassConversion.class), "value").ambiguousType())
                .isTrue();
        assertThat(column(factsFor(NativeColumn.class), "value").ambiguousType())
                .isTrue();
    }

    @Test
    void mappedSuperclassAttributesUseTheEntitysOwnTable() {
        MappedEntityFacts facts = factsFor(OwnTable.class);
        assertThat(facts.explicitTableName()).isEqualTo("own_table");
        assertThat(column(facts, "base_value").nullable()).isFalse();
        assertThat(facts.secondaryTables()).isEmpty();
    }

    @Test
    void entityInheritanceAndOverridesDoNotGuessAttributePlacement() {
        for (Class<?> type : List.of(JoinedRoot.class, JoinedChild.class, PerClassChild.class, Overridden.class)) {
            MappedEntityFacts facts = factsFor(type);
            assertThat(facts.explicitTableName()).as(type.getSimpleName()).isNull();
            assertThat(facts.columns()).isEmpty();
            assertThat(facts.foreignKeys()).isEmpty();
            assertThat(facts.secondaryTables()).isEmpty();
        }
    }

    @Test
    void discoveredEntitySubclassMakesEvenAnUnannotatedRootHierarchyUnresolved() {
        List<MappedEntityFacts> facts = HibernateSchemaBridge.toMappedEntities(List.of(
                HibernateEntityModel.fromClass(ImplicitRoot.class),
                HibernateEntityModel.fromClass(ImplicitChild.class)));
        assertThat(facts).allSatisfy(entity -> {
            assertThat(entity.explicitTableName()).isNull();
            assertThat(entity.columns()).isEmpty();
        });
    }

    @Test
    void explicitTargetEntityIsUsedInsteadOfTheAttributesInterfaceType() {
        MappedForeignKeyFacts association =
                factsFor(ExplicitTarget.class).foreignKeys().get(0);
        assertThat(association.targetTableName()).isEqualTo("customer");
        assertThat(association.targetSchema()).isEqualTo("crm");
        assertThat(association.referencedColumns()).containsExactly("id");
    }

    @Test
    void joinTableAndInconsistentCompositePlacementDoNotInventSourceTableJoins() {
        assertThat(factsFor(UnsupportedJoins.class).foreignKeys()).isEmpty();
    }

    @Test
    void duplicateSecondaryTableDeclarationsDoNotChooseAnArbitraryPlacement() {
        MappedEntityFacts facts = factsFor(DuplicateSecondaryTables.class);
        assertThat(facts.explicitTableName()).isNull();
        assertThat(facts.secondaryTables()).isEmpty();
        assertThat(facts.columns()).isEmpty();
    }

    @Test
    void individualCompositeNoConstraintSuppressesConstraintExpectation() {
        assertThat(factsFor(IndividualNoConstraint.class).foreignKeys())
                .singleElement()
                .satisfies(foreignKey ->
                        assertThat(foreignKey.constraintExpected()).isFalse());
    }

    @Test
    void transientAndCollectionAttributesAreNotMapped() {
        MappedEntityFacts facts = factsFor(Columns.class);

        assertThat(facts.columns())
                .extracting(MappedColumnFacts::columnName)
                .doesNotContain("ignored", "unnamed", "lines");
    }

    @Test
    void aColumnPinnedToASecondaryTableKeepsThatTableName() {
        MappedEntityFacts facts = factsFor(SecondaryTables.class);

        assertThat(column(facts, "detail").tableName()).isEqualTo("order_details");
        assertThat(column(facts, "blank_table").tableName()).isNull();
    }

    // --- unique constraints ---------------------------------------------------------------------------

    @Test
    void singleColumnUniqueConstraintsComeFromTheColumnItself() {
        MappedEntityFacts facts = factsFor(Columns.class);

        assertThat(facts.uniqueConstraints())
                .containsExactly(
                        new MappedUniqueConstraintFacts(Columns.class.getName() + "#email", List.of("email"), null));
    }

    @Test
    void multiColumnTableUniqueConstraintsAreReported() {
        MappedEntityFacts facts = factsFor(Order.class);

        assertThat(facts.uniqueConstraints())
                .contains(new MappedUniqueConstraintFacts(
                        Order.class.getName() + " @Table unique constraint",
                        List.of("customer_id", "reference"),
                        null));
    }

    @Test
    void secondaryTableUniqueConstraintsAreScopedToTheirTable() {
        MappedEntityFacts facts = factsFor(SecondaryTables.class);

        assertThat(facts.uniqueConstraints())
                .contains(new MappedUniqueConstraintFacts(
                        SecondaryTables.class.getName() + " @SecondaryTable(order_details) unique constraint",
                        List.of("detail"),
                        "order_details"));
    }

    // --- secondary tables -----------------------------------------------------------------------------

    @Test
    void everySecondaryTableIsReported() {
        MappedEntityFacts facts = factsFor(SecondaryTables.class);

        assertThat(facts.secondaryTables())
                .containsExactlyInAnyOrder(
                        new MappedSecondaryTableFacts("order_details", "sales", null),
                        new MappedSecondaryTableFacts("order_audit", null, null));
        assertThat(facts.secondaryTables().stream()
                        .filter(table -> table.name().equals("order_details"))
                        .findFirst()
                        .orElseThrow()
                        .qualifiedName())
                .isEqualTo("sales.order_details");
        assertThat(new MappedSecondaryTableFacts("order_audit", null, null).qualifiedName())
                .isEqualTo("order_audit");
    }

    @Test
    void anEntityWithoutSecondaryTablesReportsNone() {
        assertThat(factsFor(Order.class).secondaryTables()).isEmpty();
    }

    // --- foreign keys ---------------------------------------------------------------------------------

    @Test
    void anOwningManyToOneResolvesItsJoinColumnAndTargetTable() {
        MappedEntityFacts facts = factsFor(Order.class);

        MappedForeignKeyFacts customer = facts.foreignKeys().stream()
                .filter(fk -> fk.attributeDescription().endsWith("#customer"))
                .findFirst()
                .orElseThrow();
        assertThat(customer.columns()).containsExactly("customer_id");
        assertThat(customer.referencedColumns()).containsExactly("id");
        assertThat(customer.tableName()).isNull();
        assertThat(customer.constraintExpected()).isTrue();
        assertThat(customer.targetTableName()).isEqualTo("customer");
        assertThat(customer.targetSchema()).isEqualTo("crm");
        assertThat(customer.targetCatalog()).isNull();
        assertThat(customer.targetTableResolved()).isTrue();
    }

    @Test
    void anUnspecifiedReferencedColumnStaysUnknownRatherThanBeingGuessed() {
        MappedForeignKeyFacts invoice = foreignKey(factsFor(Order.class), "#invoice");

        assertThat(invoice.columns()).containsExactly("invoice_id");
        assertThat(invoice.referencedColumns()).containsExactly((String) null);
        assertThat(invoice.targetTableName()).isNull();
        assertThat(invoice.targetTableResolved()).isFalse();
    }

    @Test
    void anExplicitNoConstraintMappingIsNotExpectedToHaveAPhysicalForeignKey() {
        assertThat(foreignKey(factsFor(Order.class), "#warehouse").constraintExpected())
                .isFalse();
    }

    @Test
    void aJoinColumnPinnedToASecondaryTableKeepsThatTableName() {
        assertThat(foreignKey(factsFor(SecondaryTables.class), "#auditor").tableName())
                .isEqualTo("order_audit");
    }

    @Test
    void anInverseOneToOneIsNotAForeignKeyButAnOwningOneIs() {
        MappedEntityFacts facts = factsFor(Order.class);

        assertThat(facts.foreignKeys())
                .extracting(MappedForeignKeyFacts::attributeDescription)
                .noneMatch(description -> description.endsWith("#receipt"));
        assertThat(foreignKey(facts, "#shipment").columns()).containsExactly("shipment_id");
    }

    @Test
    void collectionAssociationsAndUnmappedToOnesProduceNoForeignKey() {
        MappedEntityFacts facts = factsFor(Order.class);

        assertThat(facts.foreignKeys())
                .extracting(MappedForeignKeyFacts::attributeDescription)
                .noneMatch(description -> description.endsWith("#lines") || description.endsWith("#courier"));
    }

    @Test
    void aFullyNamedCompositeJoinIsResolvedInDeclarationOrder() {
        MappedForeignKeyFacts composite = foreignKey(factsFor(CompositeJoins.class), "#parent");

        assertThat(composite.columns()).containsExactly("tenant_id", "parent_id");
        assertThat(composite.referencedColumns()).containsExactly("tenant", null);
        assertThat(composite.constraintExpected()).isTrue();
    }

    @Test
    void aPartiallyNamedCompositeJoinIsNotReportedAtAll() {
        assertThat(factsFor(CompositeJoins.class).foreignKeys())
                .extracting(MappedForeignKeyFacts::attributeDescription)
                .noneMatch(description -> description.endsWith("#partial") || description.endsWith("#empty"));
    }

    @Test
    void aNoConstraintDeclaredOnTheCompositeGroupCoversEveryJoinColumn() {
        assertThat(foreignKey(factsFor(CompositeJoins.class), "#unconstrained").constraintExpected())
                .isFalse();
    }

    private static MappedForeignKeyFacts foreignKey(MappedEntityFacts facts, String attributeSuffix) {
        return facts.foreignKeys().stream()
                .filter(fk -> fk.attributeDescription().endsWith(attributeSuffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mapped foreign key for " + attributeSuffix + " in " + facts));
    }

    // --- sequence generators --------------------------------------------------------------------------

    @Test
    void aColocatedMatchingSequenceGeneratorIsResolved() {
        MappedEntityFacts facts = factsFor(Sequences.class);

        assertThat(facts.sequenceGenerators())
                .containsExactly(new MappedSequenceGeneratorFacts(Sequences.class.getName() + "#id", "order_seq", 50));
    }

    @Test
    void ambiguousOrIncompleteSequenceGeneratorsAreNotResolved() {
        assertThat(factsFor(MismatchedGeneratorName.class).sequenceGenerators()).isEmpty();
        assertThat(factsFor(NoSequenceName.class).sequenceGenerators()).isEmpty();
        assertThat(factsFor(IdentityIdentifier.class).sequenceGenerators()).isEmpty();
        assertThat(factsFor(NoSequenceGenerator.class).sequenceGenerators()).isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------------------------

    enum Status {
        NEW,
        SHIPPED
    }

    @Entity
    @Table(
            name = "orders",
            schema = "sales",
            catalog = "warehouse",
            uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "reference"}))
    static class Order {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id", referencedColumnName = "id")
        Customer customer;

        @ManyToOne
        @JoinColumn(name = "invoice_id")
        ImplicitlyNamed invoice;

        @ManyToOne
        @JoinColumn(name = "warehouse_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        Customer warehouse;

        @ManyToOne
        Customer courier;

        @OneToOne
        @JoinColumn(name = "shipment_id")
        Customer shipment;

        @OneToOne(mappedBy = "order")
        Customer receipt;

        @OneToMany
        @JoinColumn(name = "order_id")
        Set<Customer> lines;
    }

    @Entity
    @Table(name = "customer", schema = "crm")
    static class Customer {
        @Id
        Long id;
    }

    @Entity
    static class ImplicitlyNamed {
        @Id
        Long id;
    }

    @MappedSuperclass
    @Table(name = "base_table", schema = "base_schema")
    abstract static class BaseWithTable {
        @Id
        Long id;
    }

    @Entity
    static class InheritsTable extends BaseWithTable {
        @Column(name = "name")
        String name;
    }

    @Entity
    static class Columns {
        @Id
        @Column(name = "id")
        Long id;

        @Column(name = "email", nullable = false, unique = true)
        String email;

        @Column(name = "short_code", length = 32)
        String shortCode;

        @Column(name = "default_length", length = 255)
        String defaultLength;

        @Lob
        @Column(name = "payload")
        String payload;

        @Enumerated(EnumType.STRING)
        @Column(name = "status")
        Status status;

        @Convert(converter = MoneyConverter.class)
        @Column(name = "money")
        String money;

        @Transient
        @Column(name = "ignored")
        String ignored;

        @Column
        String unnamed;

        @OneToMany
        @Column(name = "lines")
        Set<Customer> lines;
    }

    @Entity
    @SecondaryTable(
            name = "order_details",
            schema = "sales",
            uniqueConstraints = @UniqueConstraint(columnNames = {"detail"}))
    @SecondaryTable(name = "order_audit")
    static class SecondaryTables {
        @Id
        Long id;

        @Column(name = "detail", table = "order_details")
        String detail;

        @Column(name = "blank_table", table = "")
        String blankTable;

        @ManyToOne
        @JoinColumn(name = "auditor_id", table = "order_audit")
        Customer auditor;
    }

    @Entity
    static class CompositeJoins {
        @Id
        Long id;

        @ManyToOne
        @JoinColumns({@JoinColumn(name = "tenant_id", referencedColumnName = "tenant"), @JoinColumn(name = "parent_id")
        })
        Customer parent;

        @ManyToOne
        @JoinColumns({@JoinColumn(name = "tenant_id"), @JoinColumn(referencedColumnName = "id")})
        Customer partial;

        @ManyToOne
        @JoinColumns({})
        Customer empty;

        @ManyToOne
        @JoinColumns(
                value = {@JoinColumn(name = "a_id"), @JoinColumn(name = "b_id")},
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        Customer unconstrained;
    }

    @Entity
    static class Sequences {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq_gen")
        @SequenceGenerator(name = "order_seq_gen", sequenceName = "order_seq", allocationSize = 50)
        Long id;
    }

    @Entity
    static class MismatchedGeneratorName {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "other_gen")
        @SequenceGenerator(name = "order_seq_gen", sequenceName = "order_seq", allocationSize = 50)
        Long id;
    }

    @Entity
    static class NoSequenceName {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq_gen")
        @SequenceGenerator(name = "order_seq_gen", allocationSize = 50)
        Long id;
    }

    @Entity
    static class IdentityIdentifier {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY, generator = "order_seq_gen")
        @SequenceGenerator(name = "order_seq_gen", sequenceName = "order_seq", allocationSize = 50)
        Long id;
    }

    @Entity
    static class NoSequenceGenerator {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq_gen")
        Long id;
    }

    static class MoneyConverter implements jakarta.persistence.AttributeConverter<String, String> {
        @Override
        public String convertToDatabaseColumn(String attribute) {
            return attribute;
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return dbData;
        }
    }

    @Entity
    @Table(name = "defaults")
    static class AnnotationDefaults {
        @Id
        Long id;

        @Column(name = "omitted")
        String omitted;

        @Column(name = "explicit_true", nullable = true, length = 255)
        String explicitTrue;

        @Column(name = "required", nullable = false, length = 12)
        String required;

        @Column(name = "zero", length = 0)
        String zero;

        @Column(name = "negative", length = -1)
        String negative;
    }

    @Entity
    static class RepeatedConversion {
        @Id
        Long id;

        @Converts(@Convert(converter = MoneyConverter.class))
        @Column(name = "value", length = 400)
        String value;
    }

    @Entity
    @Convert(attributeName = "value", converter = MoneyConverter.class)
    static class ClassConversion {
        @Id
        Long id;

        @Column(name = "value", length = 400)
        String value;
    }

    @Entity
    static class NativeColumn {
        @Id
        Long id;

        @Column(name = "value", length = 400, columnDefinition = "jsonb")
        String value;
    }

    @MappedSuperclass
    @SecondaryTable(name = "not_inherited")
    static class InheritedAttributes {
        @Id
        Long id;

        @Column(name = "base_value", nullable = false)
        String value;
    }

    @Entity
    @Table(name = "own_table")
    static class OwnTable extends InheritedAttributes {}

    @Entity
    @Table(name = "primary")
    @SecondaryTable(name = "details", schema = "first")
    @SecondaryTable(name = "details", schema = "second")
    static class DuplicateSecondaryTables extends InheritedAttributes {}

    @Entity
    @Table(name = "root")
    @Inheritance(strategy = InheritanceType.JOINED)
    static class JoinedRoot extends InheritedAttributes {}

    @Entity
    @Table(name = "child")
    static class JoinedChild extends JoinedRoot {}

    @Entity
    @Table(name = "per_class")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    static class PerClassRoot extends InheritedAttributes {}

    @Entity
    @Table(name = "per_class_child")
    static class PerClassChild extends PerClassRoot {}

    @Entity
    @Table(name = "overridden")
    @AttributeOverride(name = "value", column = @Column(name = "overridden_value"))
    static class Overridden extends InheritedAttributes {}

    @Entity
    @Table(name = "implicit_root")
    static class ImplicitRoot extends InheritedAttributes {}

    @Entity
    static class ImplicitChild extends ImplicitRoot {}

    @Entity
    @Table(name = "explicit_target")
    static class ExplicitTarget {
        @Id
        Long id;

        @ManyToOne(targetEntity = Customer.class)
        @JoinColumn(name = "customer_id", referencedColumnName = "id")
        Object customer;
    }

    @Entity
    @Table(name = "unsupported")
    static class UnsupportedJoins {
        @Id
        Long id;

        @ManyToOne
        @JoinTable(name = "association")
        @JoinColumn(name = "customer_id", referencedColumnName = "id")
        Customer viaJoinTable;

        @ManyToOne
        @JoinColumns({@JoinColumn(name = "a", table = "first"), @JoinColumn(name = "b", table = "second")})
        Customer conflictingTables;
    }

    @Entity
    static class IndividualNoConstraint {
        @Id
        Long id;

        @ManyToOne
        @JoinColumns({
            @JoinColumn(name = "a", referencedColumnName = "a"),
            @JoinColumn(name = "b", referencedColumnName = "b", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        })
        Customer customer;
    }
}
