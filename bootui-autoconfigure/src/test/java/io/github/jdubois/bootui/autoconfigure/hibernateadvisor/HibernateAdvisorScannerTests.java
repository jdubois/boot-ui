package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorReport;
import io.github.jdubois.bootui.core.dto.HibernateAdvisorRuleResultDto;
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
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HibernateAdvisorScannerTests {

    private static final int RULE_COUNT = 61;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void scanReportsHibernateMappingAndConfigurationFindings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.jpa.open-in-view", "true");
        HibernateAdvisorScanner scanner = scanner(environment, ProblemOrder.class);

        HibernateAdvisorReport report = scanner.scan();

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
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-MAP-003"))
                .singleElement()
                .satisfies(
                        result -> assertThat(result.sampleViolations())
                                .contains(
                                        "io.github.jdubois.bootui.autoconfigure.hibernateadvisor.HibernateAdvisorScannerTests$ProblemOrder#status relies on JPA's default ORDINAL enum storage."));
    }

    @Test
    void scanReportsExpandedHibernateAdvisorFindings() {
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
        HibernateAdvisorScanner scanner = scanner(
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

        HibernateAdvisorReport report = scanner.scan();

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
                        "HIB-FETCH-007",
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
        HibernateAdvisorContext context = collectionFetchPaginationContext("7.4.9.Final");

        HibernateAdvisorRuleResultDto queryResult = new CollectionJoinFetchPageableRule().evaluate(context);
        HibernateAdvisorRuleResultDto configResult = new FailOnPaginationOverCollectionFetchRule().evaluate(context);

        assertThat(queryResult.status()).isEqualTo(HibernateAdvisorRuleSupport.VIOLATION);
        assertThat(queryResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("findPageWithTags", "o.tags"));
        assertThat(configResult.status()).isEqualTo(HibernateAdvisorRuleSupport.VIOLATION);
    }

    @Test
    void paginationOverCollectionFetchRulesSkipAfterHibernate74() {
        HibernateAdvisorContext context = collectionFetchPaginationContext("7.5.0.Final");

        HibernateAdvisorRuleResultDto queryResult = new CollectionJoinFetchPageableRule().evaluate(context);
        HibernateAdvisorRuleResultDto configResult = new FailOnPaginationOverCollectionFetchRule().evaluate(context);

        assertThat(queryResult.status()).isEqualTo(HibernateAdvisorRuleSupport.SKIPPED);
        assertThat(queryResult.sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("Hibernate 7.5.0.Final"));
        assertThat(configResult.status()).isEqualTo(HibernateAdvisorRuleSupport.SKIPPED);
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
        HibernateAdvisorScanner scanner = scanner(environment, SafeOrder.class);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void missingBatchFetchReportsLazySecondarySelectAssociations() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
        HibernateAdvisorScanner scanner = scanner(environment, BatchFetchRiskOrder.class);

        HibernateAdvisorReport report = scanner.scan();

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
        HibernateAdvisorScanner scanner =
                scanner(environment, List.of(), CoveredBatchFetchOrder.class, BatchSizedCustomer.class);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.results())
                .extracting(HibernateAdvisorRuleResultDto::id)
                .doesNotContain("HIB-FETCH-002");
    }

    @Test
    void missingBatchFetchIgnoresDefaultEagerToOneAssociations() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
        HibernateAdvisorScanner scanner = scanner(environment, DefaultEagerToOneOrder.class);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.results())
                .extracting(HibernateAdvisorRuleResultDto::id)
                .doesNotContain("HIB-FETCH-002");
    }

    @Test
    void scanReturnsStableDisabledReportWhenNoEntitiesAreAvailable() {
        HibernateAdvisorScanner scanner = new HibernateAdvisorScanner(List.of(), new MockEnvironment(), CLOCK);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.scan().message()).contains("No EntityManagerFactory beans or mapped entities");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void initialReportDoesNotInspectEntitiesBeforeExplicitScan() {
        HibernateAdvisorScanner scanner = scanner(new MockEnvironment(), ProblemOrder.class);

        HibernateAdvisorReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.entitiesAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.results()).isEmpty();
    }

    private HibernateAdvisorScanner scanner(MockEnvironment environment, Class<?> entityType) {
        return scanner(environment, List.of(), entityType);
    }

    private HibernateAdvisorScanner scanner(
            MockEnvironment environment, List<HibernateRepositoryModel> repositories, Class<?>... entityTypes) {
        return new HibernateAdvisorScanner(
                List.of(entityTypes).stream()
                        .map(HibernateEntityModel::fromClass)
                        .toList(),
                repositories,
                environment,
                CLOCK,
                "7.4.9.Final");
    }

    private HibernateAdvisorContext collectionFetchPaginationContext(String hibernateVersion) {
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
        return new HibernateAdvisorContext(
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

    static class Customer {}

    static class LineItem {}

    static class Profile {}

    static class Tag {}

    enum Status {
        NEW,
        SHIPPED
    }
}
