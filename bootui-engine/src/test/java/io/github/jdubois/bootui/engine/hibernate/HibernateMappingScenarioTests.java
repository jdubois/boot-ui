package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.envers.DefaultRevisionEntity;
import org.junit.jupiter.api.Test;

/**
 * Regression corpus for tricky JPA mapping scenarios, one test per scenario and rule, named
 * {@code scenarioNN_<what>_<rule>}. A test ending in {@code _currentBehaviour_knownGap} pins what the advisor
 * does today where the intended outcome needs a larger change; it is not an endorsement of that behaviour.
 *
 * <p>Behavioural claims about Hibernate itself (what it does with an unmatched {@code @MapsId}, an inverse lazy
 * {@code @OneToOne}, {@code @Immutable}, Envers) were verified against Hibernate ORM 7.4.5.Final, the version the
 * pinned Spring Boot BOM selects, and are recorded in the comment of the scenario that relies on them.</p>
 */
class HibernateMappingScenarioTests {

    private static final String VIOLATION = HibernateRuleSupport.VIOLATION;
    private static final String PASS = HibernateRuleSupport.PASS;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);

    private static HibernateContext declarations(Class<?>... types) {
        return new HibernateContext(
                List.of(types).stream().map(HibernateEntityModel::fromClass).toList(),
                List.of(),
                key -> null,
                List.of(),
                "7.4.5.Final");
    }

    private static HibernateContext observed(
            TestEnvironment values, HibernateFactorySettings settings, Class<?>... types) {
        return HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "orders",
                        "orders",
                        List.of(types).stream()
                                .map(HibernateEntityModel::fromClass)
                                .toList(),
                        List.of(),
                        "7.4.5.Final",
                        settings,
                        false),
                HibernateObservationFixtures.application(values));
    }

    private static HibernateRuleResultDto evaluate(AbstractHibernateRule rule, Class<?>... types) {
        return rule.evaluate(declarations(types));
    }

    private static void assertNoRuleErrors(HibernateContext context) {
        for (HibernateRule rule : HibernateRuleRegistry.activeRules()) {
            assertThat(rule.evaluate(context).status())
                    .as(rule.definition().id())
                    .isNotEqualTo(HibernateRuleSupport.ERROR);
        }
    }

    // --- S1: @MapsId naming an attribute the identifier does not have -------------------------------------------

    @Test
    void scenario01_validSharedPrimaryKeys_HIB_MAP_006() {
        assertThat(evaluate(new OneToOneWithoutMapsIdRule(), BlankMapsId.class, NamedMapsId.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario01_separateForeignKey_HIB_MAP_006_stillReported() {
        HibernateRuleResultDto result = evaluate(new OneToOneWithoutMapsIdRule(), SeparateForeignKey.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample ->
                        assertThat(sample).contains("SeparateForeignKey#parent").contains("without @MapsId"));
    }

    /**
     * Verified on Hibernate ORM 7.4.5.Final: {@code @MapsId("ID")} on an entity whose identifier attribute is
     * {@code id} does not generate a separate foreign key; bootstrapping fails with "Identifier field 'ID' named in
     * '@MapsId' does not exist in entity", and the match is case-sensitive. A persistence unit that reaches the
     * advisor therefore never contains this mapping, so the rule's skip of every {@code @MapsId} attribute cannot
     * hide a separate-FK mapping on the baseline, and changing the rule would be dead code. This test pins the rule's
     * current skip of every {@code @MapsId} attribute; it is not a claim about Hibernate behaviour.
     */
    @Test
    void scenario01_unmatchedMapsId_HIB_MAP_006_currentBehaviour_knownGap() {
        HibernateRuleResultDto result = evaluate(new OneToOneWithoutMapsIdRule(), UnmatchedMapsId.class);

        assertThat(result.status()).isEqualTo(PASS);
    }

    // --- S2: self-referencing owning @OneToOne -------------------------------------------------------------------

    @Test
    void scenario02_selfReferencingOneToOne_HIB_MAP_006() {
        HibernateRuleResultDto result = evaluate(new OneToOneWithoutMapsIdRule(), Employee.class);

        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void scenario02_selfReferencingMandatoryCascadingOneToOne_HIB_MAP_006() {
        assertThat(evaluate(new OneToOneWithoutMapsIdRule(), DependentEmployee.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario02_oneToOneWithinTheSameInheritanceHierarchy_HIB_MAP_006() {
        assertThat(evaluate(new OneToOneWithoutMapsIdRule(), Manager.class).status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario02_oneToOneBetweenSiblingSubclasses_HIB_MAP_006() {
        assertThat(evaluate(new OneToOneWithoutMapsIdRule(), SiblingA.class, SiblingB.class)
                        .status())
                .isEqualTo(PASS);
    }

    /** Sharing only a {@code @MappedSuperclass} does not put two entities in one identifier space. */
    @Test
    void scenario02_oneToOneBetweenEntitiesSharingOnlyAMappedSuperclass_HIB_MAP_006_stillReported() {
        HibernateRuleResultDto result = evaluate(new OneToOneWithoutMapsIdRule(), MappedA.class, MappedB.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("MappedA#counterpart"));
    }

    @Test
    void scenario02_selfReferenceThroughTargetEntity_HIB_MAP_006() {
        assertThat(evaluate(new OneToOneWithoutMapsIdRule(), TargetEntitySelfReference.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario02_oneToOneToAnotherEntity_HIB_MAP_006_stillReported() {
        HibernateRuleResultDto result = evaluate(new OneToOneWithoutMapsIdRule(), Badge.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("Badge#holder"));
    }

    // --- S3: inverse lazy @OneToOne ----------------------------------------------------------------------------

    /**
     * Verified on Hibernate ORM 7.4.5.Final with query-count tests (loading the parent by id, no enhancement):
     * <ul>
     *   <li>owning side with a separate unique foreign key: the lazy inverse side is NOT proxied, even with
     *       {@code optional=false}; a second SELECT returns an initialized instance, so the rule keeps reporting;</li>
     *   <li>owning side sharing the primary key ({@code @MapsId} or {@code @PrimaryKeyJoinColumn}) with
     *       {@code optional=false}: the inverse side IS proxied, uninitialized, with a single statement, so the rule
     *       must not report it;</li>
     *   <li>the same shared-key owner with {@code optional=true}: not proxied, so the rule still reports it.</li>
     * </ul>
     */
    @Test
    void scenario03_inverseLazyOneToOneNotOptional_HIB_MAP_018() {
        HibernateRuleResultDto result = evaluate(new NonOwningOneToOneEnhancementRule(), InverseRequiredParent.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("InverseRequiredParent#detail"));
    }

    @Test
    void scenario03_inverseLazyOneToOneOptional_HIB_MAP_018() {
        HibernateRuleResultDto result = evaluate(new NonOwningOneToOneEnhancementRule(), InverseOptionalParent.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("InverseOptionalParent#detail"));
    }

    @Test
    void scenario03_inverseLazyMandatoryOneToOneWithMapsIdOwner_HIB_MAP_018_notReported() {
        assertThat(evaluate(new NonOwningOneToOneEnhancementRule(), SharedKeyMapsIdParent.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario03_inverseLazyMandatoryOneToOneWithPrimaryKeyJoinColumnOwner_HIB_MAP_018_notReported() {
        assertThat(evaluate(new NonOwningOneToOneEnhancementRule(), SharedKeyPkJoinParent.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario03_inverseLazyOptionalOneToOneWithMapsIdOwner_HIB_MAP_018_stillReported() {
        HibernateRuleResultDto result = evaluate(new NonOwningOneToOneEnhancementRule(), SharedKeyOptionalParent.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("SharedKeyOptionalParent#detail"));
    }

    @Test
    void scenario03_inverseEagerOneToOne_HIB_MAP_018_notReported() {
        assertThat(evaluate(new NonOwningOneToOneEnhancementRule(), InverseEagerParent.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario03_inverseOneToOneIsNeverASharedPrimaryKeyCandidate_HIB_MAP_006() {
        assertThat(evaluate(
                                new OneToOneWithoutMapsIdRule(),
                                InverseRequiredParent.class,
                                InverseOptionalParent.class,
                                InverseEagerParent.class)
                        .status())
                .isEqualTo(PASS);
    }

    // --- S4: @Immutable entity versus @Immutable collection -----------------------------------------------------

    /**
     * Verified on Hibernate ORM 7.4.5.Final: an entity-level {@code @Immutable} ignores changes to the entity's
     * basic state (no UPDATE), but its collections stay modifiable: adding to a join-column {@code @OneToMany} or
     * an {@code @ElementCollection} still flushes collection DML. The mutation-cost rules therefore keep applying,
     * as they do for a collection that is itself {@code @Immutable} (see the known-gap scenarios below).
     */
    @Test
    void scenario04_immutableEntityKeepsMutableCollections_HIB_MAP_001_010_020() {
        assertThat(evaluate(new UnidirectionalOneToManyRule(), ImmutableEntityWithCollections.class)
                        .status())
                .isEqualTo(VIOLATION);
        assertThat(evaluate(new ElementCollectionListOrderRule(), ImmutableEntityWithCollections.class)
                        .status())
                .isEqualTo(VIOLATION);
        assertThat(evaluate(new UnidirectionalOneToManyJoinColumnRule(), ImmutableEntityWithCollections.class)
                        .status())
                .isEqualTo(VIOLATION);
    }

    /**
     * Verified on Hibernate ORM 7.4.5.Final: an attribute-level {@code @Immutable} collection rejects in-place
     * changes ({@code add}, {@code remove}, {@code clear} throw "Immutable collection was modified"), but it does
     * not remove the DML the rules review. Persisting a new owner still issues the join-column
     * {@code UPDATE ... SET owner_id}, and assigning a replacement list to the attribute still issues a
     * collection-wide DELETE and INSERT. Exempting such collections would therefore hide real findings, so the
     * rules keep reporting them. Narrowing the rules to the cases where the DML cannot occur would need evidence
     * about how the application uses the collection, which a declaration does not carry.
     */
    @Test
    void scenario04_immutableCollection_HIB_MAP_010_020_currentBehaviour_knownGap() {
        assertThat(evaluate(new UnidirectionalOneToManyJoinColumnRule(), ImmutableCollections.class)
                        .sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("ImmutableCollections#joinColumnLines"));
        assertThat(evaluate(new ElementCollectionListOrderRule(), ImmutableCollections.class)
                        .sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("ImmutableCollections#tags"));
    }

    @Test
    void scenario04_immutableCollectionWithJoinTable_HIB_MAP_001_currentBehaviour_knownGap() {
        HibernateRuleResultDto result = evaluate(new UnidirectionalOneToManyRule(), ImmutableCollections.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("ImmutableCollections#joinTableLines"));
    }

    /**
     * Verified on Hibernate ORM 7.4.5.Final: {@code @OptimisticLocking(DIRTY|ALL)} without {@code @DynamicUpdate}
     * fails bootstrap with "has 'OptimisticLockType.ALL' but is not annotated '@DynamicUpdate'", whether or not the
     * entity is {@code @Immutable}, so the declaration cannot reach the advisor on the baseline. The rule keeps
     * reporting it; retiring HIB-ENTITY-002 is a catalogue decision and is left to a follow-up.
     */
    @Test
    void scenario04_immutableEntityWithVersionlessLocking_HIB_ENTITY_002_currentBehaviour_knownGap() {
        HibernateRuleResultDto result = evaluate(new OptimisticLockingDynamicUpdateRule(), ImmutableVersionless.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(evaluate(new OptimisticLockingDynamicUpdateRule(), ImmutableVersionlessDynamic.class)
                        .status())
                .isEqualTo(PASS);
    }

    // --- S5: @ElementCollection of enums -----------------------------------------------------------------------

    @Test
    void scenario05_onlyTheUnorderedEnumListIsReported_HIB_MAP_010() {
        HibernateRuleResultDto result = evaluate(new ElementCollectionListOrderRule(), EnumCollections.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample)
                        .endsWith("EnumCollections#colorList is an @ElementCollection List without @OrderColumn."));
    }

    // --- S6: Hibernate Envers ------------------------------------------------------------------------------------

    /**
     * Verified on Hibernate ORM 7.4.5.Final with Envers: an {@code @Audited} entity's generated {@code *_AUD}
     * entity is map-based and is absent from {@code Metamodel.getEntities()}, so it never reaches a rule. Envers
     * does add its own {@code org.hibernate.envers.DefaultRevisionEntity} (table {@code REVINFO}) to that metamodel,
     * which no application can edit, so discovery leaves it out.
     */
    @Test
    void scenario06_enversRevisionEntityIsNotDiscovered_allMappingRules() {
        EntityDiscovery discovery = JpaMetamodelReader.readEntities(
                List.of(factoryOf(entityType(DefaultRevisionEntity.class), entityType(AuditedOrder.class))));

        assertThat(discovery.errors()).isEmpty();
        assertThat(discovery.entities())
                .extracting(HibernateEntityModel::name)
                .containsExactly(AuditedOrder.class.getName());
    }

    @Test
    void scenario06_applicationDeclaredRevisionEntityIsStillDiscovered_allMappingRules() {
        EntityDiscovery discovery =
                JpaMetamodelReader.readEntities(List.of(factoryOf(entityType(CustomRevisionEntity.class))));

        assertThat(discovery.entities())
                .extracting(HibernateEntityModel::name)
                .containsExactly(CustomRevisionEntity.class.getName());
    }

    @Test
    void scenario06_theRevisionEntityWouldOtherwiseProduceUnactionableFindings_HIB_ENTITY_008_MAP_011() {
        assertThat(evaluate(new MissingVersionRule(), DefaultRevisionEntity.class)
                        .status())
                .isEqualTo(VIOLATION);
        assertThat(evaluate(new FinalEntityRule(), DefaultRevisionEntity.class).status())
                .isEqualTo(VIOLATION);
    }

    // --- S7: equals/hashCode inherited from a base class -------------------------------------------------------

    @Test
    void scenario07_equalsAndHashCodeInheritedTogether_HIB_ENTITY_001() {
        assertThat(evaluate(new EqualsHashCodePairRule(), PersistableShapedEntity.class, OwnPairEntity.class)
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void scenario07_onlyOneInherited_HIB_ENTITY_001() {
        HibernateRuleResultDto result =
                evaluate(new EqualsHashCodePairRule(), InheritsEqualsOnly.class, InheritsHashCodeOnly.class);

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations())
                .anySatisfy(sample ->
                        assertThat(sample).contains("InheritsEqualsOnly").contains("equals but not hashCode"))
                .anySatisfy(sample ->
                        assertThat(sample).contains("InheritsHashCodeOnly").contains("hashCode but not equals"));
    }

    // --- S8: managed connection provider -------------------------------------------------------------------------

    @Test
    void scenario08_managedConnectionProviderPasses_HIB_CONFIG_014() {
        TestEnvironment values = new TestEnvironment();
        HibernateFactorySettings managed = HibernateObservationFixtures.settings(values);
        assertThat(managed.connectionProvider()).isEqualTo(HibernateFactorySettings.ConnectionProvider.MANAGED);

        HibernateRuleResultDto result =
                new HibernateBuiltinPoolRule().evaluate(observed(values, managed, BlankMapsId.class));

        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void scenario08_builtInConnectionProviderIsReported_HIB_CONFIG_014() {
        TestEnvironment values = new TestEnvironment();
        HibernateFactorySettings builtIn =
                HibernateObservationFixtures.settings(values, HibernateFactorySettings.ConnectionProvider.BUILT_IN);

        HibernateRuleResultDto result =
                new HibernateBuiltinPoolRule().evaluate(observed(values, builtIn, BlankMapsId.class));

        assertThat(result.status()).isEqualTo(VIOLATION);
    }

    @Test
    void scenario08_unknownConnectionProviderIsNotAFinding_HIB_CONFIG_014() {
        TestEnvironment values = new TestEnvironment();
        HibernateFactorySettings unknown =
                HibernateObservationFixtures.settings(values, HibernateFactorySettings.ConnectionProvider.UNKNOWN);

        HibernateRuleResultDto result =
                new HibernateBuiltinPoolRule().evaluate(observed(values, unknown, BlankMapsId.class));

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    // --- S9: @NaturalId with the second-level cache off ----------------------------------------------------------

    @Test
    void scenario09_naturalIdEntityIsNotReportedWithSecondLevelCacheOff_HIB_ENTITY_009() {
        TestEnvironment values = secondLevelCacheOff();

        HibernateRuleResultDto result = new NaturalIdCandidateRule()
                .evaluate(observed(values, HibernateObservationFixtures.settings(values), NaturalIdKeyed.class));

        assertThat(result.status()).isEqualTo(PASS);
    }

    @Test
    void scenario09_advisoryNeverClaimsACacheEffect_HIB_ENTITY_009() {
        TestEnvironment values = secondLevelCacheOff();

        HibernateRuleResultDto result = new NaturalIdCandidateRule()
                .evaluate(
                        observed(values, HibernateObservationFixtures.settings(values), UniqueWithoutNaturalId.class));

        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.description()).doesNotContainIgnoringCase("cache");
        assertThat(result.sampleViolations())
                .allSatisfy(sample -> assertThat(sample).doesNotContainIgnoringCase("cache"));
        // The only mention of a cache denies an automatic effect.
        assertThat(result.recommendation()).contains("does not automatically");
    }

    private static TestEnvironment secondLevelCacheOff() {
        return new TestEnvironment().withProperty("hibernate.cache.use_second_level_cache", "false");
    }

    // --- S11: scans must survive unusual mappings -------------------------------------------------------------------

    @Test
    void scenario11_genericEmbeddedIdReachesNoRuleError_allRules() {
        assertNoRuleErrors(declarations(GenericEmbeddedIdEntity.class, RawGenericEmbeddedIdEntity.class));
    }

    @Test
    void scenario11_identityOneToOneTargetReachesNoRuleError_allRules() {
        assertNoRuleErrors(declarations(IdentityOwner.class, IdentityTarget.class));
        HibernateRuleResultDto result =
                evaluate(new OneToOneWithoutMapsIdRule(), IdentityOwner.class, IdentityTarget.class);
        assertThat(result.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("IdentityTarget#owner"));
    }

    @Test
    void scenario11_scanCompletesWithoutRuleErrorDiagnostics_allRules() {
        TestEnvironment values = new TestEnvironment();
        HibernateScanner scanner = HibernateScanner.observing(
                () -> new HibernateAdvisorObservation(
                        List.of(new HibernatePersistenceUnitObservation(
                                "test",
                                "test",
                                List.of(
                                                GenericEmbeddedIdEntity.class,
                                                RawGenericEmbeddedIdEntity.class,
                                                IdentityOwner.class,
                                                IdentityTarget.class,
                                                HbmXmlOnlyPojo.class)
                                        .stream()
                                        .map(HibernateEntityModel::fromClass)
                                        .toList(),
                                List.of(),
                                "7.4.5.Final",
                                HibernateObservationFixtures.settings(values),
                                false)),
                        HibernateObservationFixtures.application(values),
                        List.of()),
                CLOCK);

        HibernateReport report = scanner.scan();

        assertThat(report.entitiesAnalyzed()).isEqualTo(5);
        assertThat(report.diagnostics()).noneMatch(diagnostic -> "ERROR".equals(diagnostic.level()));
    }

    /**
     * An entity mapped only through {@code .hbm.xml} has no annotations to read, so declaration-based rules see
     * every attribute as unannotated. Verified end to end on Hibernate ORM 7.4.5.Final: such an entity is
     * discovered and no rule errors, but annotation-absence findings such as HIB-MAP-013 are reported for columns
     * whose length the XML may well declare, and no diagnostic or coverage note tells the user that the XML
     * mapping was not read. Reading the XML or disclosing the gap is larger than this regression corpus.
     */
    @Test
    void scenario11_hbmXmlOnlyEntity_allRules_currentBehaviour_knownGap() {
        HibernateContext context = declarations(HbmXmlOnlyPojo.class);

        assertNoRuleErrors(context);
        HibernateRuleResultDto stringLength = new StringColumnLengthRule().evaluate(context);
        assertThat(stringLength.status()).isEqualTo(VIOLATION);
        assertThat(stringLength.sampleViolations())
                .singleElement()
                .satisfies(sample -> assertThat(sample).contains("HbmXmlOnlyPojo#name"));
        assertThat(stringLength.coverageNote()).isNull();
    }

    // --- Metamodel doubles -----------------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityType<?> entityType(Class<?> javaType) {
        EntityType entityType = mock(EntityType.class);
        when(entityType.getJavaType()).thenReturn(javaType);
        when(entityType.getName()).thenReturn(javaType.getSimpleName());
        when(entityType.getAttributes()).thenReturn(Set.of());
        return entityType;
    }

    private static jakarta.persistence.EntityManagerFactory factoryOf(EntityType<?>... entityTypes) {
        jakarta.persistence.EntityManagerFactory factory = mock(jakarta.persistence.EntityManagerFactory.class);
        Metamodel metamodel = mock(Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(entityTypes));
        return factory;
    }

    // --- Fixtures ---------------------------------------------------------------------------------------------------

    @Entity
    static class MapsIdParent {
        @Id
        Long id;
    }

    @Entity
    static class BlankMapsId {
        @Id
        Long id;

        @OneToOne
        @MapsId
        MapsIdParent parent;
    }

    @Entity
    static class NamedMapsId {
        @Id
        Long id;

        @OneToOne
        @MapsId("id")
        MapsIdParent parent;
    }

    @Entity
    static class UnmatchedMapsId {
        @Id
        Long id;

        @OneToOne
        @MapsId("ID")
        MapsIdParent parent;
    }

    @Entity
    static class SeparateForeignKey {
        @Id
        Long id;

        @OneToOne
        MapsIdParent parent;
    }

    @Entity
    static class Employee {
        @Id
        Long id;

        @OneToOne
        Employee mentor;
    }

    @Entity
    static class DependentEmployee {
        @Id
        Long id;

        @OneToOne(optional = false, cascade = jakarta.persistence.CascadeType.ALL)
        DependentEmployee sponsor;
    }

    @Entity
    static class Manager extends Employee {
        @OneToOne
        Employee assistant;
    }

    @Entity
    static class SiblingRoot {
        @Id
        Long id;
    }

    @Entity
    static class SiblingA extends SiblingRoot {
        @OneToOne
        SiblingB counterpart;
    }

    @Entity
    static class SiblingB extends SiblingRoot {}

    @MappedSuperclass
    abstract static class IdBase {
        @Id
        Long id;
    }

    @Entity
    static class MappedA extends IdBase {
        @OneToOne
        MappedB counterpart;
    }

    @Entity
    static class MappedB extends IdBase {}

    @Entity
    static class TargetEntitySelfReference {
        @Id
        Long id;

        @OneToOne(targetEntity = TargetEntitySelfReference.class)
        Object successor;
    }

    @Entity
    static class Badge {
        @Id
        Long id;

        @OneToOne(optional = false)
        Employee holder;
    }

    @Entity
    static class InverseChild {
        @Id
        Long id;

        @OneToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "parent_id", nullable = false, unique = true)
        InverseRequiredParent parent;
    }

    @Entity
    static class InverseRequiredParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.LAZY, optional = false)
        InverseChild detail;
    }

    @Entity
    static class InverseOptionalParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.LAZY, optional = true)
        InverseChild detail;
    }

    @Entity
    static class SharedKeyMapsIdChild {
        @Id
        Long id;

        @OneToOne(fetch = FetchType.LAZY, optional = false)
        @MapsId
        SharedKeyMapsIdParent parent;
    }

    @Entity
    static class SharedKeyMapsIdParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.LAZY, optional = false)
        SharedKeyMapsIdChild detail;
    }

    @Entity
    static class SharedKeyPkJoinChild {
        @Id
        Long id;

        @OneToOne(fetch = FetchType.LAZY, optional = false)
        @PrimaryKeyJoinColumn
        SharedKeyPkJoinParent parent;
    }

    @Entity
    static class SharedKeyPkJoinParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.LAZY, optional = false)
        SharedKeyPkJoinChild detail;
    }

    @Entity
    static class SharedKeyOptionalChild {
        @Id
        Long id;

        @OneToOne(fetch = FetchType.LAZY)
        @MapsId
        SharedKeyOptionalParent parent;
    }

    @Entity
    static class SharedKeyOptionalParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.LAZY, optional = true)
        SharedKeyOptionalChild detail;
    }

    @Entity
    static class InverseEagerParent {
        @Id
        Long id;

        @OneToOne(mappedBy = "parent", fetch = FetchType.EAGER)
        InverseChild detail;
    }

    @Entity
    static class Line {
        @Id
        Long id;
    }

    @Entity
    @Immutable
    static class ImmutableEntityWithCollections {
        @Id
        Long id;

        @OneToMany
        @JoinColumn(name = "owner_id")
        List<Line> joinColumnLines;

        @OneToMany
        List<Line> joinTableLines;

        @ElementCollection
        List<String> tags;
    }

    @Entity
    static class ImmutableCollections {
        @Id
        Long id;

        @OneToMany
        @JoinColumn(name = "owner_id")
        @Immutable
        List<Line> joinColumnLines;

        @OneToMany
        @Immutable
        List<Line> joinTableLines;

        @ElementCollection
        @Immutable
        List<String> tags;
    }

    @Entity
    @Immutable
    @OptimisticLocking(type = OptimisticLockType.ALL)
    static class ImmutableVersionless {
        @Id
        Long id;

        String name;
    }

    @Entity
    @Immutable
    @DynamicUpdate
    @OptimisticLocking(type = OptimisticLockType.ALL)
    static class ImmutableVersionlessDynamic {
        @Id
        Long id;

        String name;
    }

    enum Color {
        RED,
        GREEN
    }

    @Entity
    static class EnumCollections {
        @Id
        Long id;

        @ElementCollection
        Set<Color> colorSet;

        @ElementCollection
        Map<Color, Integer> colorWeights;

        @ElementCollection
        List<Color> colorList;

        @ElementCollection
        @OrderColumn
        List<Color> colorOrderedList;
    }

    @Entity
    static class AuditedOrder {
        @Id
        Long id;
    }

    @Entity
    static class CustomRevisionEntity extends org.hibernate.envers.RevisionMapping {}

    @MappedSuperclass
    abstract static class PersistableShapedBase {
        @Id
        @GeneratedValue
        Long id;

        @Override
        public boolean equals(Object other) {
            return other instanceof PersistableShapedBase base
                    && getClass().equals(base.getClass())
                    && Objects.equals(id, base.id);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    @Entity
    static class PersistableShapedEntity extends PersistableShapedBase {
        String name;
    }

    @Entity
    static class OwnPairEntity {
        @Id
        Long id;

        @Override
        public boolean equals(Object other) {
            return other instanceof OwnPairEntity entity && Objects.equals(id, entity.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }

    @MappedSuperclass
    abstract static class EqualsOnlyBase {
        @Id
        Long id;

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualsOnlyBase base && Objects.equals(id, base.id);
        }
    }

    @Entity
    static class InheritsEqualsOnly extends EqualsOnlyBase {}

    @MappedSuperclass
    abstract static class HashCodeOnlyBase {
        @Id
        Long id;

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    @Entity
    static class InheritsHashCodeOnly extends HashCodeOnlyBase {}

    @Entity
    static class NaturalIdKeyed {
        @Id
        Long id;

        @NaturalId
        @Column(unique = true)
        String isbn;
    }

    @Entity
    static class UniqueWithoutNaturalId {
        @Id
        Long id;

        @Column(unique = true)
        String email;
    }

    @MappedSuperclass
    abstract static class GenericKeyBase<T extends Serializable> implements Serializable {
        T first;
        Long second;

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericKeyBase<?> key
                    && Objects.equals(first, key.first)
                    && Objects.equals(second, key.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

    @Embeddable
    static class StringKey extends GenericKeyBase<String> {
        public StringKey() {}
    }

    @Entity
    static class GenericEmbeddedIdEntity {
        @EmbeddedId
        StringKey id;

        String name;
    }

    @Embeddable
    static class GenericKey<T extends Serializable> implements Serializable {
        T first;
        Long second;

        public GenericKey() {}

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericKey<?> key
                    && Objects.equals(first, key.first)
                    && Objects.equals(second, key.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

    @Entity
    static class RawGenericEmbeddedIdEntity {
        @EmbeddedId
        GenericKey<String> id;
    }

    @Entity
    static class IdentityOwner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @OneToOne(mappedBy = "owner")
        IdentityTarget target;
    }

    @Entity
    static class IdentityTarget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @OneToOne
        @JoinColumn(name = "owner_id")
        IdentityOwner owner;
    }

    /** What an entity mapped only in {@code .hbm.xml} looks like to reflection: a plain, unannotated class. */
    static class HbmXmlOnlyPojo {
        Long id;
        String name;
        Set<String> tags;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Set<String> getTags() {
            return tags;
        }
    }
}
