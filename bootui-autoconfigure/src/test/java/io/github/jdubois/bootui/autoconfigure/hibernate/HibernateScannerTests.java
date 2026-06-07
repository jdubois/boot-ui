package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Basic;
import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Convert;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HibernateScannerTests {

    private static final int RULE_COUNT = 63;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void scanReportsHibernateMappingAndConfigurationFindings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.jpa.open-in-view", "true");
        HibernateScanner scanner = scanner(environment, ProblemOrder.class);

        HibernateReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.entitiesAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.results())
                .extracting(result -> result.id())
                .contains(
                        "HIB-FETCH-001",
                        "HIB-CONFIG-001",
                        "HIB-ID-001",
                        "HIB-MAP-001",
                        "HIB-MAP-002",
                        "HIB-MAP-003",
                        "HIB-FETCH-002",
                        "HIB-CONFIG-002");
        assertThat(report.results())
                .anySatisfy(result -> assertThat(result.sampleViolations())
                        .anySatisfy(sample -> assertThat(sample).contains("customer is mapped as FetchType.EAGER.")));
        assertThat(report.results()).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo("HIB-FETCH-001");
            assertThat(result.name()).isEqualTo("Eager fetching should stay explicit and bounded");
            assertThat(result.sampleViolations())
                    .anySatisfy(sample ->
                            assertThat(sample).contains("labels is an @ElementCollection mapped as FetchType.EAGER."));
        });
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-MAP-003"))
                .singleElement()
                .satisfies(
                        result -> assertThat(result.sampleViolations())
                                .contains(
                                        "io.github.jdubois.bootui.autoconfigure.hibernate.HibernateScannerTests$ProblemOrder#status relies on JPA's default ORDINAL enum storage."));
    }

    @Test
    void scanReportsExpandedHibernateFindings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.open-in-view", "false")
                .withProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "true")
                .withProperty("spring.jpa.properties.hibernate.jdbc.batch_size", "25")
                .withProperty("spring.jpa.properties.hibernate.cache.use_query_cache", "true")
                .withProperty("spring.jpa.properties.hibernate.cache.use_second_level_cache", "false")
                .withProperty("spring.jpa.properties.hibernate.cache.region.factory_class", "jcache")
                .withProperty("spring.jpa.properties.hibernate.connection.pool_size", "5")
                .withProperty("spring.jpa.defer-datasource-initialization", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.jpa.show-sql", "true");
        environment.setActiveProfiles("prod");
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.ProblemOrderRepository",
                ProblemOrder.class,
                List.of(
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "findPageWithTags",
                                ProblemOrder.class,
                                List.class,
                                "select o from ProblemOrder o left join fetch o.tags where o.status = :status",
                                false,
                                null,
                                true,
                                false,
                                false,
                                false,
                                List.of(Status.class)),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "findByIds",
                                ProblemOrder.class,
                                List.class,
                                "select o from ProblemOrder o where o.id in :ids",
                                false,
                                null,
                                false,
                                false,
                                false,
                                false,
                                List.of(List.class)),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "deleteAllStatus",
                                ProblemOrder.class,
                                int.class,
                                "delete from ProblemOrder o where o.status = :status",
                                false,
                                null,
                                false,
                                true,
                                false,
                                false,
                                List.of(Status.class)),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "streamAll",
                                ProblemOrder.class,
                                Stream.class,
                                null,
                                false,
                                null,
                                false,
                                false,
                                false,
                                false,
                                List.of()),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "findPageNative",
                                ProblemOrder.class,
                                List.class,
                                "select * from problem_order where status = ?",
                                true,
                                null,
                                true,
                                false,
                                false,
                                false,
                                List.of(Status.class)),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "deleteByStatus",
                                ProblemOrder.class,
                                long.class,
                                null,
                                false,
                                null,
                                false,
                                false,
                                false,
                                false,
                                List.of(Status.class))));
        HibernateScanner scanner = scanner(
                environment,
                List.of(repository),
                ProblemOrder.class,
                TableIdentifierEntity.class,
                SequenceEntity.class,
                TablePerClassEntity.class,
                VersionlessEntity.class,
                CacheableEntity.class,
                UncachedAssociation.class,
                SingleTableRootEntity.class,
                LegacyMappingEntity.class,
                FinalEntity.class,
                ReadOnlyCacheEntity.class,
                AutoGeneratedIdEntity.class,
                UuidIdentifierEntity.class);

        HibernateReport report = scanner.scan();

        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.results())
                .extracting(result -> result.id())
                .contains(
                        "HIB-CONFIG-003",
                        "HIB-CONFIG-005",
                        "HIB-CONFIG-006",
                        "HIB-CONFIG-007",
                        "HIB-CONFIG-008",
                        "HIB-CONFIG-009",
                        "HIB-CONFIG-010",
                        "HIB-CONFIG-011",
                        "HIB-CONFIG-012",
                        "HIB-CONFIG-013",
                        "HIB-CONFIG-014",
                        "HIB-CONFIG-015",
                        "HIB-FETCH-003",
                        "HIB-FETCH-004",
                        "HIB-FETCH-005",
                        "HIB-FETCH-006",
                        "HIB-ID-002",
                        "HIB-ID-003",
                        "HIB-ID-004",
                        "HIB-ID-005",
                        "HIB-MAP-004",
                        "HIB-MAP-005",
                        "HIB-MAP-006",
                        "HIB-MAP-007",
                        "HIB-MAP-008",
                        "HIB-MAP-009",
                        "HIB-MAP-010",
                        "HIB-MAP-011",
                        "HIB-MAP-012",
                        "HIB-MAP-013",
                        "HIB-MAP-014",
                        "HIB-MAP-015",
                        "HIB-MAP-016",
                        "HIB-MAP-017",
                        "HIB-ENTITY-002",
                        "HIB-ENTITY-003",
                        "HIB-ENTITY-004",
                        "HIB-ENTITY-005",
                        "HIB-QUERY-001",
                        "HIB-QUERY-002",
                        "HIB-QUERY-003",
                        "HIB-QUERY-004",
                        "HIB-CACHE-001",
                        "HIB-CACHE-002");
    }

    @Test
    void paginationOverCollectionFetchRulesStillApplyThroughHibernate74() {
        HibernateContext context = collectionFetchPaginationContext("7.4.9.Final");

        HibernateRuleResultDto queryResult = new CollectionJoinFetchPageableRule().evaluate(context);
        HibernateRuleResultDto configResult = new FailOnPaginationOverCollectionFetchRule().evaluate(context);

        assertThat(queryResult.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(queryResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("findPageWithTags", "o.tags"));
        assertThat(configResult.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
    }

    @Test
    void paginationOverCollectionFetchRulesSkipAfterHibernate74() {
        HibernateContext context = collectionFetchPaginationContext("7.5.0.Final");

        HibernateRuleResultDto queryResult = new CollectionJoinFetchPageableRule().evaluate(context);
        HibernateRuleResultDto configResult = new FailOnPaginationOverCollectionFetchRule().evaluate(context);

        assertThat(queryResult.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(queryResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Hibernate 7.5.0.Final"));
        assertThat(configResult.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(configResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Hibernate 7.5.0.Final"));
    }

    @Test
    void hibernateVersionComparisonUsesMajorMinorNumbers() {
        assertThat(HibernateRuntimeVersion.parse("7.4.99.Final").isAfterMajorMinor(7, 4))
                .isFalse();
        assertThat(HibernateRuntimeVersion.parse("7.10.0.Final").isAfterMajorMinor(7, 4))
                .isTrue();
        assertThat(HibernateRuntimeVersion.parse("8.0.0.Final").isAfterMajorMinor(7, 4))
                .isTrue();
        assertThat(HibernateRuntimeVersion.parse("unknown").isAfterMajorMinor(7, 4))
                .isFalse();
    }

    @Test
    void scanPassesWhenMappingsAndConfigurationAreSafe() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.open-in-view", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create")
                .withProperty("hibernate.default_batch_fetch_size", "32")
                .withProperty("hibernate.jdbc.batch_size", "25")
                .withProperty("hibernate.jdbc.time_zone", "UTC")
                .withProperty("hibernate.order_inserts", "true")
                .withProperty("hibernate.order_updates", "true")
                .withProperty("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", "25")
                .withProperty("hibernate.generate_statistics", "true")
                .withProperty("hibernate.connection.provider_disables_autocommit", "true")
                .withProperty("hibernate.query.in_clause_parameter_padding", "true")
                .withProperty("hibernate.query.fail_on_pagination_over_collection_fetch", "true");

        environment.setActiveProfiles("test");
        HibernateScanner scanner = scanner(environment, SafeOrder.class);

        HibernateReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void missingBatchFetchReportsLazySecondarySelectAssociations() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
        HibernateScanner scanner = scanner(environment, BatchFetchRiskOrder.class);

        HibernateReport report = scanner.scan();

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-FETCH-002"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(3);
                    assertThat(result.sampleViolations())
                            .contains(
                                    BatchFetchRiskOrder.class.getName()
                                            + "#customer can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.",
                                    BatchFetchRiskOrder.class.getName()
                                            + "#lineItems can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.",
                                    BatchFetchRiskOrder.class.getName()
                                            + "#tags can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.");
                    assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("#defaultEagerCustomer"));
                });
    }

    @Test
    void missingBatchFetchHonorsLocalAndTargetBatchSizeAnnotations() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
        HibernateScanner scanner =
                scanner(environment, List.of(), CoveredBatchFetchOrder.class, BatchSizedCustomer.class);

        HibernateReport report = scanner.scan();

        assertThat(report.results()).extracting(HibernateRuleResultDto::id).doesNotContain("HIB-FETCH-002");
    }

    @Test
    void missingBatchFetchIgnoresDefaultEagerToOneAssociations() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
        HibernateScanner scanner = scanner(environment, DefaultEagerToOneOrder.class);

        HibernateReport report = scanner.scan();

        assertThat(report.results()).extracting(HibernateRuleResultDto::id).doesNotContain("HIB-FETCH-002");
    }

    @Test
    void scanReturnsStableDisabledReportWhenNoEntitiesAreAvailable() {
        HibernateScanner scanner = new HibernateScanner(List.of(), new MockEnvironment(), CLOCK);

        HibernateReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.scan().message()).contains("No EntityManagerFactory beans or mapped entities");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void initialReportDoesNotInspectEntitiesBeforeExplicitScan() {
        HibernateScanner scanner = scanner(new MockEnvironment(), ProblemOrder.class);

        HibernateReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.entitiesAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void eagerToOneFetchJoinRuleFlagsEntityQueriesWithoutJoinFetch() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.ProblemOrderRepository",
                ProblemOrder.class,
                List.of(
                        queryMethod("findAllOrders", List.class, "select o from ProblemOrder o", false, false),
                        queryMethod(
                                "findWithCustomer",
                                List.class,
                                "select o from ProblemOrder o join fetch o.customer",
                                false,
                                false),
                        queryMethod("findIds", List.class, "select o.id from ProblemOrder o", false, false),
                        queryMethod("findNative", List.class, "select * from problem_order", true, false)));
        HibernateContext context = new HibernateContext(
                List.of(HibernateEntityModel.fromClass(ProblemOrder.class)),
                List.of(repository),
                new MockEnvironment());

        HibernateRuleResultDto result = new EagerToOneFetchJoinRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("findAllOrders", "customer"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findWithCustomer"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findIds"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findNative"));
    }

    @Test
    void eagerToOneFetchJoinRuleSkipsWhenNoRepositoriesAreAvailable() {
        HibernateContext context = new HibernateContext(
                List.of(HibernateEntityModel.fromClass(ProblemOrder.class)), List.of(), new MockEnvironment());

        HibernateRuleResultDto result = new EagerToOneFetchJoinRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
    }

    @Test
    void entityProjectionQueryRuleFlagsPagedAndStreamedWholeEntitySelects() {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.ProblemOrderRepository",
                ProblemOrder.class,
                List.of(
                        queryMethod("findPage", List.class, "select o from ProblemOrder o", false, true),
                        queryMethod("streamAll", Stream.class, "select o from ProblemOrder o", false, false),
                        queryMethod(
                                "findPageDto",
                                List.class,
                                "select new com.example.OrderView(o.id, o.status) from ProblemOrder o",
                                false,
                                true),
                        queryMethod("findIdsPage", List.class, "select o.id from ProblemOrder o", false, true),
                        queryMethod("findAll", List.class, "select o from ProblemOrder o", false, false)));
        HibernateContext context = new HibernateContext(
                List.of(HibernateEntityModel.fromClass(ProblemOrder.class)),
                List.of(repository),
                new MockEnvironment());

        HibernateRuleResultDto result = new EntityProjectionQueryRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("findPage"));
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("streamAll"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findPageDto"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findIdsPage"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("findAll "));
    }

    @Test
    void missingVersionRuleFlagsMutableEntitiesWithoutVersion() {
        HibernateContext context = entitiesContext(
                MutableNoVersionEntity.class,
                VersionedEntity.class,
                ImmutableMutableEntity.class,
                IdOnlyEntity.class,
                VersionlessOptimisticEntity.class);

        HibernateRuleResultDto result = new MissingVersionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains(MutableNoVersionEntity.class.getName()));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("VersionedEntity"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("ImmutableMutableEntity"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("IdOnlyEntity"));
        assertThat(result.sampleViolations()).noneMatch(sample -> sample.contains("VersionlessOptimisticEntity"));
    }

    private HibernateRepositoryMethodModel queryMethod(
            String methodName, Class<?> returnType, String query, boolean nativeQuery, boolean pageable) {
        return new HibernateRepositoryMethodModel(
                "com.example.ProblemOrderRepository",
                methodName,
                ProblemOrder.class,
                returnType,
                query,
                nativeQuery,
                null,
                pageable,
                false,
                false,
                false,
                List.of());
    }

    private HibernateContext entitiesContext(Class<?>... entityTypes) {
        return new HibernateContext(
                List.of(entityTypes).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                List.of(),
                new MockEnvironment());
    }

    @Test
    void ruleEvaluationWrapsRuntimeExceptionAsErrorResult() {
        HibernateRuleResultDto result = new ThrowingRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).hasSize(1);
        assertThat(result.sampleViolations().get(0))
                .contains("Rule could not be evaluated:")
                .contains("boom");
    }

    @Test
    void ruleEvaluationWrapsLinkageErrorAsErrorResult() {
        HibernateRuleResultDto result = new LinkageErrorRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.ERROR);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations().get(0)).contains("missing");
    }

    @Test
    void skippedRuleSurfacesSkippedStatusAndReason() {
        HibernateRuleResultDto result = new SkippingRule().evaluate(emptyContext());

        assertThat(result.status()).isEqualTo(HibernateRuleSupport.SKIPPED);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).containsExactly("Not applicable in this context.");
    }

    @Test
    void everyActiveRuleRoutesThroughTheFailClosedBase() {
        assertThat(HibernateRuleRegistry.activeRules())
                .allSatisfy(rule -> assertThat(rule).isInstanceOf(AbstractHibernateRule.class));
    }

    private static HibernateContext emptyContext() {
        return new HibernateContext(List.of(), List.of(), new MockEnvironment());
    }

    private static HibernateRuleDefinition testRuleDefinition() {
        return new HibernateRuleDefinition(
                "HIB-TEST-001",
                "Deliberately failing test rule",
                HibernateCategory.CONFIGURATION,
                "LOW",
                "Test-only rule used to exercise the fail-closed base.",
                "No action required.",
                null);
    }

    private static final class ThrowingRule extends AbstractHibernateRule {

        ThrowingRule() {
            super(testRuleDefinition());
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class LinkageErrorRule extends AbstractHibernateRule {

        LinkageErrorRule() {
            super(testRuleDefinition());
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            throw new NoClassDefFoundError("missing");
        }
    }

    private static final class SkippingRule extends AbstractHibernateRule {

        SkippingRule() {
            super(testRuleDefinition());
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            return skipped("Not applicable in this context.");
        }
    }

    private HibernateScanner scanner(MockEnvironment environment, Class<?> entityType) {
        return scanner(environment, List.of(), entityType);
    }

    private HibernateScanner scanner(
            MockEnvironment environment, List<HibernateRepositoryModel> repositories, Class<?>... entityTypes) {
        return new HibernateScanner(
                List.of(entityTypes).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                environment,
                CLOCK,
                "7.4.9.Final");
    }

    private HibernateContext collectionFetchPaginationContext(String hibernateVersion) {
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.ProblemOrderRepository",
                ProblemOrder.class,
                List.of(new HibernateRepositoryMethodModel(
                        "com.example.ProblemOrderRepository",
                        "findPageWithTags",
                        ProblemOrder.class,
                        List.class,
                        "select o from ProblemOrder o left join fetch o.tags where o.status = :status",
                        false,
                        null,
                        true,
                        false,
                        false,
                        false,
                        List.of(Status.class))));
        return new HibernateContext(
                List.of(HibernateEntityModel.fromClass(ProblemOrder.class)),
                List.of(repository),
                new MockEnvironment(),
                hibernateVersion);
    }

    @Entity
    static class ProblemOrder {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;

        @ManyToOne(cascade = CascadeType.REMOVE)
        Customer customer;

        @OneToMany
        List<LineItem> lineItems;

        @ManyToMany(cascade = CascadeType.ALL)
        @Fetch(FetchMode.JOIN)
        List<Tag> tags;

        @OneToOne(fetch = FetchType.LAZY)
        Profile profile;

        @ManyToOne(fetch = FetchType.LAZY)
        @NotFound(action = NotFoundAction.IGNORE)
        Customer legacyCustomer;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "owner_id", nullable = false)
        Customer mandatoryOwner;

        @Lob
        String payload;

        @ElementCollection(fetch = FetchType.EAGER)
        Set<String> labels;

        @ElementCollection
        List<String> commentsList;

        BigDecimal price;

        Date legacyTimestamp;

        Optional<String> notes;

        String description;

        Status status;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProblemOrder that)) {
                return false;
            }
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "ProblemOrder{id=" + id + "}";
        }
    }

    @Entity
    static class TableIdentifierEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.TABLE)
        Long id;
    }

    @Entity
    static class SequenceEntity {

        @Id
        @SequenceGenerator(name = "sequence_entity_seq", allocationSize = 1)
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequence_entity_seq")
        Long id;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    static class TablePerClassEntity {

        @Id
        Long id;
    }

    @Entity
    @OptimisticLocking(type = OptimisticLockType.DIRTY)
    static class VersionlessEntity {

        @Id
        Long id;
    }

    @Entity
    @Cacheable
    static class CacheableEntity {

        @Id
        Long id;

        @OneToMany
        List<UncachedAssociation> details;
    }

    @Entity
    static class BatchFetchRiskOrder {

        @Id
        Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        Customer customer;

        @ManyToOne
        Customer defaultEagerCustomer;

        @OneToMany
        List<LineItem> lineItems;

        @ManyToMany
        Set<Tag> tags;
    }

    @Entity
    static class CoveredBatchFetchOrder {

        @Id
        Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        BatchSizedCustomer customer;

        @OneToMany
        @BatchSize(size = 16)
        List<LineItem> lineItems;
    }

    @Entity
    @BatchSize(size = 32)
    static class BatchSizedCustomer {

        @Id
        Long id;
    }

    @Entity
    static class DefaultEagerToOneOrder {

        @Id
        Long id;

        @ManyToOne
        Customer customer;

        @OneToOne
        Profile profile;
    }

    @Entity
    static class UncachedAssociation {

        @Id
        Long id;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class SingleTableRootEntity {

        @Id
        Long id;
    }

    @Entity
    static class LegacyMappingEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        Long id;

        @Lob
        @Basic(fetch = FetchType.LAZY)
        String document;

        Date legacyDate;

        BigDecimal amount;

        String name;
    }

    @Entity
    static final class FinalEntity {

        @Id
        Long id;
    }

    @Entity
    @DynamicUpdate
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    static class ReadOnlyCacheEntity {

        @Id
        Long id;

        @jakarta.persistence.Version
        Long version;
    }

    @Entity
    static class AutoGeneratedIdEntity {

        @Id
        @GeneratedValue
        Long id;
    }

    @Entity
    static class UuidIdentifierEntity {

        @Id
        @GeneratedValue
        UUID id;
    }

    @Entity
    @Table(indexes = @Index(name = "idx_customer", columnList = "customer_id"))
    @DiscriminatorColumn
    static class SafeOrder {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        Long id;

        @jakarta.persistence.Version
        Long version;

        @ManyToOne(fetch = FetchType.LAZY)
        Customer customer;

        @OneToMany(mappedBy = "order")
        List<LineItem> lineItems;

        @OneToMany
        @JoinColumn(name = "order_id")
        @OrderColumn(name = "line_item_order")
        List<LineItem> ownedLineItems;

        @ManyToMany
        Set<Tag> tags;

        @Enumerated(EnumType.STRING)
        Status status;

        @Enumerated(EnumType.ORDINAL)
        Status stableOrdinalStatus;

        @Convert(converter = StatusCodeConverter.class)
        Status convertedStatus;
    }

    static class StatusCodeConverter implements AttributeConverter<Status, Integer> {

        @Override
        public Integer convertToDatabaseColumn(Status attribute) {
            if (attribute == null) {
                return null;
            }
            return switch (attribute) {
                case NEW -> 10;
                case SHIPPED -> 20;
            };
        }

        @Override
        public Status convertToEntityAttribute(Integer dbData) {
            if (dbData == null) {
                return null;
            }
            return switch (dbData) {
                case 10 -> Status.NEW;
                case 20 -> Status.SHIPPED;
                default -> throw new IllegalArgumentException("Unknown status code: " + dbData);
            };
        }
    }

    @Entity
    static class MutableNoVersionEntity {

        @Id
        Long id;

        String name;
    }

    @Entity
    static class VersionedEntity {

        @Id
        Long id;

        String name;

        @jakarta.persistence.Version
        Long version;
    }

    @Entity
    @Immutable
    static class ImmutableMutableEntity {

        @Id
        Long id;

        String name;
    }

    @Entity
    static class IdOnlyEntity {

        @Id
        Long id;
    }

    @Entity
    @OptimisticLocking(type = OptimisticLockType.ALL)
    @DynamicUpdate
    static class VersionlessOptimisticEntity {

        @Id
        Long id;

        String name;
    }

    static class Customer {}

    static class LineItem {}

    static class Profile {}

    static class Tag {}

    enum Status {
        NEW,
        SHIPPED
    }
}
