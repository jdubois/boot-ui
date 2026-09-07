package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

class HibernateAdvisorObservationTests {
    private static final Clock CLOCK = Clock.systemUTC();
    private static final HibernateApplicationFacts APP = new HibernateApplicationFacts(
            List.of("prod"), HibernateApplicationFacts.OpenInView.NOT_APPLICABLE, false, false, false, false);

    @Test
    void selectedCatalogTitlesAndSeveritiesStayScoped() {
        Map<String, List<String>> expected = Map.of(
                "HIB-ID-002", List.of("Review table-based identifier allocation", "MEDIUM"),
                "HIB-ID-004", List.of("Review provider-selected identifier strategies", "INFO"),
                "HIB-MAP-007", List.of("Review TABLE_PER_CLASS polymorphic queries", "INFO"),
                "HIB-QUERY-002", List.of("Review streaming query resource lifetime", "INFO"),
                "HIB-ENTITY-006", List.of("Review primitive-version newness semantics", "INFO"),
                "HIB-MAP-018", List.of("Review lazy inverse @OneToOne without enhancement", "MEDIUM"),
                "HIB-CONFIG-001", List.of("Open Session in View should be disabled", "MEDIUM"));
        expected.forEach((id, values) -> {
            HibernateRuleDefinition definition = HibernateRuleRegistry.activeRules().stream()
                    .map(HibernateRule::definition)
                    .filter(rule -> id.equals(rule.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(List.of(definition.name(), definition.severity())).isEqualTo(values);
        });
    }

    @Test
    void primitiveVersionAdviceRequiresStandardJpaNewnessAndDoesNotTargetIds() {
        List<Class<?>> types =
                List.of(PrimitiveVersion.class, NullableVersion.class, OnlyPrimitiveId.class, SubPersistable.class);
        for (Boolean standard : new Boolean[] {true, false, null}) {
            List<HibernateRepositoryModel> repositories = standard == null
                    ? List.of()
                    : types.stream()
                            .map(type -> new HibernateRepositoryModel("Repo", type, List.of(), standard))
                            .toList();
            HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                    "orders",
                    "orders",
                    types.stream().map(HibernateEntityModel::fromClass).toList(),
                    repositories,
                    "7.4.5.Final",
                    settings(0),
                    false);
            HibernateReport report = scanner(List.of(unit), List.of(), List.of(new PrimitiveIdentifierOrVersionRule()))
                    .scan();
            if (Boolean.TRUE.equals(standard)) {
                assertThat(report.results()).singleElement().satisfies(result -> {
                    assertThat(result.id()).isEqualTo("HIB-ENTITY-006");
                    assertThat(result.severity()).isEqualTo("INFO");
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .contains("PrimitiveVersion#version");
                });
            } else {
                assertThat(report.results()).isEmpty();
            }
            assertThat(report.scan().status()).isEqualTo(Boolean.FALSE.equals(standard) ? "PARTIAL" : "SCANNED");
        }
    }

    @Test
    void unitSettingsNeverBleedAcrossFactoriesAndIdentityIsScoredOnlyOncePerUnit() {
        HibernatePersistenceUnitObservation small = unit("small", settings(1), List.of());
        HibernatePersistenceUnitObservation batched = unit("batched", settings(25), List.of());
        List<HibernateRule> rules = List.of(
                new IdentityIdentifierRule(),
                new IdentityDisablesBatchingRule(),
                new JdbcBatchSizeRule(),
                new OrderedBatchingRule());
        for (List<HibernatePersistenceUnitObservation> units :
                List.of(List.of(small, batched), List.of(batched, small))) {
            HibernateReport report = scanner(units, List.of(), rules).scan();
            assertThat(report.entitiesAnalyzed()).isEqualTo(2);
            assertThat(report.rulesEvaluated()).isEqualTo(4);
            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(result(report, "HIB-ID-001").sampleViolations()).allMatch(value -> value.startsWith("[small]"));
            assertThat(result(report, "HIB-ID-006").sampleViolations())
                    .allMatch(value -> value.startsWith("[batched]"));
            assertThat(result(report, "HIB-CONFIG-004").sampleViolations())
                    .allMatch(value -> value.startsWith("[small]"));
            assertThat(result(report, "HIB-CONFIG-005").violationCount()).isEqualTo(2);
        }
    }

    @Test
    void batchSizesZeroOneTwoTwentyFiveAndUnknownHaveDistinctEvidence() {
        for (Integer batch : new Integer[] {0, 1, 2, 25, null}) {
            HibernateReport report = scanner(
                            List.of(unit(
                                    "orders",
                                    batch == null ? HibernateFactorySettings.unknown() : settings(batch),
                                    List.of())),
                            List.of(),
                            List.of(
                                    new IdentityIdentifierRule(),
                                    new IdentityDisablesBatchingRule(),
                                    new JdbcBatchSizeRule()))
                    .scan();
            if (batch == null) {
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactly("HIB-ID-001");
            } else if (batch <= 1) {
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactlyInAnyOrder("HIB-ID-001", "HIB-CONFIG-004");
            } else {
                assertThat(report.results())
                        .extracting(HibernateRuleResultDto::id)
                        .containsExactly("HIB-ID-006");
            }
        }
    }

    @Test
    void missingUpdateOrderingDoesNotDiscardKnownInsertOrderingViolation() {
        HibernateFactorySettings settings = new HibernateFactorySettings(
                25, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        HibernateReport report = scanner(
                        List.of(unit("orders", settings, List.of())), List.of(), List.of(new OrderedBatchingRule()))
                .scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("HIB-CONFIG-005");
            assertThat(result.violationCount()).isEqualTo(1);
            assertThat(result.sampleViolations()).singleElement().asString().contains("insert-ordering");
        });
    }

    @Test
    void aggregationKeepsRealCountsAndTenSamplesAndDismissalKey() {
        List<HibernatePersistenceUnitObservation> units = new ArrayList<>();
        for (int i = 0; i < 14; i++) units.add(unit("unit-" + i, settings(25), List.of()));
        HibernateScanner scanner = scanner(units, List.of(), List.of(new IdentityDisablesBatchingRule()));
        HibernateReport report = scanner.scan();
        assertThat(report.results()).hasSize(1);
        assertThat(result(report, "HIB-ID-006").violationCount()).isEqualTo(14);
        assertThat(result(report, "HIB-ID-006").sampleViolations()).hasSize(10);
        assertThat(report.rulesEvaluated()).isEqualTo(1);
        HibernateReport dismissed = scanner.applyDismissals(report, Set.of("HIB-ID-006"));
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.results())
                .singleElement()
                .satisfies(value -> assertThat(value.dismissed()).isTrue());
    }

    @Test
    void ruleRuntimeAndLinkageFailuresMakeReadableFindingsPartialWithoutLeakingMessages() {
        for (boolean linkage : List.of(false, true)) {
            HibernateRule throwing = testRule("HIB-TEST-ERROR", context -> {
                if (linkage) throw new NoClassDefFoundError("secret-jdbc-password");
                throw new IllegalStateException("secret-jdbc-password");
            });
            HibernateReport report = scanner(
                            List.of(unit("orders", settings(25), List.of())),
                            List.of(new HibernateObservationDiagnostic(
                                    "failed-unit", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE)),
                            List.of(new IdentityDisablesBatchingRule(), throwing))
                    .scan();
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.scan().message())
                    .contains("failed 1", "HIB-TEST-ERROR", "failed-unit")
                    .doesNotContain("secret-jdbc-password");
            assertThat(report.results()).extracting(HibernateRuleResultDto::id).containsExactly("HIB-ID-006");
        }
    }

    @Test
    void requiredUnknownDiffersFromIntentionalInapplicability() {
        HibernatePersistenceUnitObservation unit = unit("orders", HibernateFactorySettings.unknown(), List.of());
        HibernateReport missing = scanner(List.of(unit), List.of(), List.of(new JdbcBatchSizeRule()))
                .scan();
        assertThat(missing.scan().status()).isEqualTo("PARTIAL");
        assertThat(missing.results()).isEmpty();
        assertThat(missing.scan().message()).contains("required evidence unavailable 1");
        HibernateReport panache = scanner(
                        List.of(unit),
                        List.of(),
                        List.of(
                                new EagerToOneFetchJoinRule(),
                                new CollectionJoinFetchPageableRule(),
                                new InClausePaddingRule(),
                                new OpenInViewRule()))
                .scan();
        assertThat(panache.scan().status()).isEqualTo("SCANNED");
        assertThat(panache.scan().message()).contains("skipped 4", "required evidence unavailable 0");
    }

    @Test
    void initialReadIsLazyAndTotalSourceFailureIsIncompleteRatherThanDisabled() {
        AtomicInteger calls = new AtomicInteger();
        HibernateScanner scanner = HibernateScanner.observing(
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("credential");
                },
                CLOCK);
        assertThat(scanner.initialReport().scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(calls).hasValue(0);
        HibernateReport report = scanner.scan();
        assertThat(calls).hasValue(1);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).doesNotContain("credential");
    }

    @Test
    void compatibilitySourceDoesNotPromoteGlobalPropertiesToEffectiveFactorySettings() {
        HibernateScanner scanner = HibernateScanner.using(
                () -> new EntityDiscovery(List.of(HibernateEntityModel.fromClass(Order.class)), List.of(), List.of()),
                key -> "hibernate.jdbc.batch_size".equals(key) ? "25" : null,
                List::of,
                CLOCK);
        HibernateReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).extracting(HibernateRuleResultDto::id).doesNotContain("HIB-ID-006");
    }

    @Test
    void fiveRetiredIdsAreNotRegisteredOrReported() {
        assertThat(HibernateRuleRegistry.activeRules()).hasSize(70);
        assertThat(HibernateRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContain("HIB-FETCH-004", "HIB-MAP-012", "HIB-ENTITY-003", "HIB-ENTITY-004", "HIB-MAP-019");
        HibernateReport report = HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                List.of(unit("orders", settings(0), List.of())), APP, List.of()),
                        CLOCK)
                .scan();
        assertThat(report.results())
                .extracting(HibernateRuleResultDto::id)
                .doesNotContain("HIB-FETCH-004", "HIB-MAP-012", "HIB-ENTITY-003", "HIB-ENTITY-004", "HIB-MAP-019");
    }

    @Test
    void customEntityNameResolvesButJavaSimpleNameIsNotAnAlternativeRoot() {
        for (String root : List.of("Purchase", "AliasedOrder")) {
            HibernateRepositoryMethodModel query =
                    method("select o from " + root + " o join fetch o.items", null, AliasedOrder.class, false);
            HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                    "orders",
                    "orders",
                    List.of(
                            HibernateEntityModel.fromClass(AliasedOrder.class),
                            HibernateEntityModel.fromClass(Other.class)),
                    List.of(new HibernateRepositoryModel("Repo", AliasedOrder.class, List.of(query), true)),
                    "7.2.19.Final",
                    settings(0),
                    false);
            HibernateReport report = scanner(List.of(unit), List.of(), List.of(new CollectionJoinFetchPageableRule()))
                    .scan();
            if ("Purchase".equals(root)) {
                assertThat(report.scan().status()).isEqualTo("SCANNED");
                assertThat(report.results()).singleElement().satisfies(result -> {
                    assertThat(result.id()).isEqualTo("HIB-FETCH-003");
                    assertThat(result.violationCount()).isEqualTo(1);
                });
            } else {
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(report.results()).isEmpty();
            }
        }
    }

    @Test
    void paginationUsesObservedRootAndDeclaredHintRatherThanRepositoryDomainOrVersionGuess() {
        for (Boolean hint : new Boolean[] {true, false, null}) {
            HibernateRepositoryMethodModel method =
                    method("select o from Order o join fetch o.items", hint, Order.class, false);
            HibernateContext context = context("7.4.5.Final", List.of(method));
            HibernateRuleResultDto fetch = new CollectionJoinFetchPageableRule().evaluate(context);
            HibernateRuleResultDto guard = new FailOnPaginationOverCollectionFetchRule().evaluate(context);
            if (Boolean.TRUE.equals(hint)) {
                assertThat(fetch.violationCount()).isEqualTo(1);
                assertThat(guard.violationCount()).isEqualTo(1);
            } else {
                assertThat(fetch.violationCount()).isZero();
                assertThat(guard.violationCount()).isZero();
                assertThat(context.evidence().requiredUnknown).isTrue();
            }
        }
        HibernateContext legacy = context(
                "7.2.19.Final", List.of(method("select o from Order o join fetch o.items", null, Order.class, false)));
        assertThat(new FailOnPaginationOverCollectionFetchRule()
                        .evaluate(legacy)
                        .violationCount())
                .isEqualTo(1);
    }

    @Test
    void queryEligibilityRejectsProjectionSubqueryAndNestedAliasesAndIgnoresCommentsAndLiterals() {
        for (String query : List.of(
                "select o from Order o where o.id in (select x.id from Other x) join fetch o.items",
                "select o from Order o join o.items i join fetch i.tags",
                "select o from Order o join fetch o.items i")) {
            HibernateContext context = context("7.2.19.Final", List.of(method(query, null, Order.class, false)));
            assertThat(new CollectionJoinFetchPageableRule().evaluate(context).violationCount())
                    .isZero();
            assertThat(context.evidence().requiredUnknown).isTrue();
        }
        HibernateContext harmless = context(
                "7.2.19.Final",
                List.of(method(
                        "select o from Order o /* join fetch o.items */ where o.name = 'join fetch o.items'",
                        null,
                        Order.class,
                        false)));
        assertThat(new CollectionJoinFetchPageableRule().evaluate(harmless).violationCount())
                .isZero();
        HibernateContext projection = context(
                "7.2.19.Final",
                List.of(method("select o from Order o join fetch o.items", null, Projection.class, false)));
        assertThat(new CollectionJoinFetchPageableRule().evaluate(projection).violationCount())
                .isZero();
    }

    @Test
    void resolvedRootCanDifferFromRepositoryDomainAndGraphIsNotAssumedToFixFetches() {
        HibernateRepositoryMethodModel method = method("select o from Order o", null, Order.class, true);
        HibernateContext context = context("7.2.19.Final", List.of(method));
        assertThat(HibernateQueryShape.entityRoot(context, method).javaType()).isEqualTo(Order.class);
        assertThat(new EagerToOneFetchJoinRule().evaluate(context).violationCount())
                .isZero();
        assertThat(context.evidence().requiredUnknown).isTrue();
    }

    @Test
    void compositeReadOnlyJoinColumnsAreExemptOnlyWhenEveryColumnIsReadOnly() {
        HibernateContext context = declarations(ReadOnlyJoins.class, PartlyWritableJoins.class);
        HibernateRuleResultDto result = new UnidirectionalOneToManyJoinColumnRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("PartlyWritableJoins"));
    }

    @Test
    void immutableVersionDoesNotProveWritableAndRoleCacheDoesNotCoverTargetState() {
        HibernateContext context = declarations(ImmutableVersioned.class, CachedOwner.class, Other.class);
        assertThat(new ReadOnlyCacheOnWritableEntityRule().evaluate(context).violationCount())
                .isZero();
        HibernateRuleResultDto coverage = new CacheAssociationCoverageRule().evaluate(context);
        assertThat(coverage.sampleViolations()).anyMatch(value -> value.contains("CachedOwner#targets"));
    }

    @Test
    void persistableSubinterfaceAndNullableVersionExemptAssignedIdentifierAdvice() {
        List<HibernateRepositoryModel> repositories = List.of(
                new HibernateRepositoryModel("Repo", SubPersistable.class, List.of(), true),
                new HibernateRepositoryModel("Repo", NullableVersion.class, List.of(), true),
                new HibernateRepositoryModel("Repo", PrimitiveVersion.class, List.of(), true));
        HibernateContext context = new HibernateContext(
                List.of(SubPersistable.class, NullableVersion.class, PrimitiveVersion.class).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                key -> null,
                List.of());
        HibernateRuleResultDto result = new AssignedIdPersistableRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("PrimitiveVersion"));
    }

    private static HibernateRule testRule(
            String id, java.util.function.Function<HibernateContext, HibernateRuleResultDto> body) {
        return new AbstractHibernateRule(new HibernateRuleDefinition(
                id, "Test", HibernateCategory.CONFIGURATION, "INFO", "Test", "Test", null)) {
            @Override
            HibernateRuleResultDto evaluateRule(HibernateContext context) {
                return body.apply(context);
            }
        };
    }

    @Test
    void emittedKotlinNullableAndPrimitiveVersionsAndNoArgVersusOpenAreDistinct() throws Exception {
        Class<?> nullable = io.github.jdubois.bootui.engine.hibernate.kotlinfixtures.KotlinNullableVersionEntity.class;
        Class<?> primitive =
                io.github.jdubois.bootui.engine.hibernate.kotlinfixtures.KotlinPrimitiveVersionEntity.class;
        assertThat(nullable.getDeclaredConstructor()).isNotNull();
        assertThat(java.lang.reflect.Modifier.isFinal(nullable.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(primitive.getModifiers())).isFalse();
        List<HibernateRepositoryModel> repositories = List.of(
                new HibernateRepositoryModel("Nullable", nullable, List.of(), true),
                new HibernateRepositoryModel("Primitive", primitive, List.of(), true));
        HibernateContext context = new HibernateContext(
                List.of(nullable, primitive).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                key -> null,
                List.of());
        HibernateRuleResultDto result = new AssignedIdPersistableRule().evaluate(context);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations()).allMatch(value -> value.contains("KotlinPrimitiveVersionEntity"));
        HibernateReport scan = HibernateScanner.observing(
                        () -> new HibernateAdvisorObservation(
                                List.of(new HibernatePersistenceUnitObservation(
                                        "kotlin",
                                        "kotlin",
                                        List.of(
                                                HibernateEntityModel.fromClass(
                                                        io.github.jdubois.bootui.engine.hibernate.kotlinfixtures
                                                                .KotlinBodyAssociationEntity.class),
                                                HibernateEntityModel.fromClass(
                                                        io.github.jdubois.bootui.engine.hibernate.kotlinfixtures
                                                                .KotlinConstructorAssociationEntity.class)),
                                        List.of(),
                                        "7.4.5.Final",
                                        settings(0),
                                        false)),
                                APP,
                                List.of()),
                        CLOCK)
                .scan();
        assertThat(scan.results())
                .extracting(HibernateRuleResultDto::id)
                .doesNotContain("HIB-ENTITY-003", "HIB-ENTITY-004");
    }

    private static HibernateScanner scanner(
            List<HibernatePersistenceUnitObservation> units,
            List<HibernateObservationDiagnostic> diagnostics,
            List<HibernateRule> rules) {
        return new HibernateScanner(() -> new HibernateAdvisorObservation(units, APP, diagnostics), CLOCK, rules);
    }

    private static HibernateRuleResultDto result(HibernateReport report, String id) {
        return report.results().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static HibernateFactorySettings settings(int batch) {
        return HibernateObservationFixtures.settings(
                new TestEnvironment().withProperty("hibernate.jdbc.batch_size", Integer.toString(batch)));
    }

    private static HibernatePersistenceUnitObservation unit(
            String label, HibernateFactorySettings settings, List<HibernateRepositoryModel> repositories) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(Order.class)),
                repositories,
                "7.2.19.Final",
                settings,
                false);
    }

    private static HibernateContext context(String version, List<HibernateRepositoryMethodModel> methods) {
        return HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "orders",
                        "orders",
                        List.of(
                                HibernateEntityModel.fromClass(Order.class),
                                HibernateEntityModel.fromClass(Other.class)),
                        List.of(new HibernateRepositoryModel("Repo", Other.class, methods, true)),
                        version,
                        settings(0),
                        false),
                APP);
    }

    private static HibernateRepositoryMethodModel method(String query, Boolean hint, Class<?> element, boolean graph) {
        return new HibernateRepositoryMethodModel(
                "Repo",
                "query",
                Other.class,
                List.class,
                query,
                false,
                null,
                true,
                false,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(true, true, false, false, false, element, graph, "FETCH", hint, List.of()));
    }

    private static HibernateContext declarations(Class<?>... types) {
        return new HibernateContext(
                List.of(types).stream().map(HibernateEntityModel::fromClass).toList(),
                List.of(),
                key -> null,
                List.of());
    }

    @Entity(name = "Purchase")
    static class AliasedOrder {
        @Id
        Long id;

        @OneToMany
        List<Other> items;
    }

    @Entity
    static class Order {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ManyToOne
        Other owner;

        @OneToMany
        List<Other> items;

        @OneToMany
        List<Other> tags;

        String name;

        @Override
        public boolean equals(Object other) {
            return other instanceof Order order && id != null && id.equals(order.id);
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return "Order:" + id;
        }
    }

    @Entity
    static class Other {
        @Id
        Long id;
    }

    interface Projection {}

    @Entity
    static class ReadOnlyJoins {
        @Id
        Long id;

        @OneToMany
        @JoinColumns({
            @JoinColumn(name = "a", insertable = false, updatable = false),
            @JoinColumn(name = "b", insertable = false, updatable = false)
        })
        List<Other> targets;
    }

    @Entity
    static class PartlyWritableJoins {
        @Id
        Long id;

        @OneToMany
        @JoinColumns({@JoinColumn(name = "a", insertable = false, updatable = false), @JoinColumn(name = "b")})
        List<Other> targets;
    }

    @Entity
    @Immutable
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    static class ImmutableVersioned {
        @Id
        Long id;

        @Version
        Long version;
    }

    @Entity
    @Cacheable
    static class CachedOwner {
        @Id
        Long id;

        @OneToMany
        @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
        List<Other> targets;
    }

    interface Newness<T> extends Persistable<T> {}

    @Entity
    static class OnlyPrimitiveId {
        @Id
        long id;
    }

    @Entity
    static class SubPersistable implements Newness<Long> {
        @Id
        Long id;

        @Version
        long version;

        public Long getId() {
            return id;
        }

        public boolean isNew() {
            return true;
        }
    }

    @Entity
    static class NullableVersion {
        @Id
        Long id;

        @Version
        Long version;
    }

    @Entity
    static class PrimitiveVersion {
        @Id
        Long id;

        @Version
        long version;
    }
}
