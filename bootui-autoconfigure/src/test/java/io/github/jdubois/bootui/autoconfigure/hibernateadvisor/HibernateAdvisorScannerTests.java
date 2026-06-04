package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorReport;
import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.SequenceGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HibernateAdvisorScannerTests {

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
        assertThat(report.rulesEvaluated()).isEqualTo(29);
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
        assertThat(report.results().get(0).sampleViolations())
                .anySatisfy(sample -> assertThat(sample).contains("customer is mapped as FetchType.EAGER."));
    }

    @Test
    void scanReportsExpandedHibernateAdvisorFindings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.open-in-view", "false")
                .withProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "true")
                .withProperty("spring.jpa.properties.hibernate.jdbc.batch_size", "25")
                .withProperty("spring.jpa.properties.hibernate.cache.use_query_cache", "true")
                .withProperty("spring.jpa.properties.hibernate.cache.use_second_level_cache", "false")
                .withProperty("spring.jpa.properties.hibernate.cache.region.factory_class", "jcache");
        HibernateRepositoryModel repository = new HibernateRepositoryModel(
                "com.example.ProblemOrderRepository",
                ProblemOrder.class,
                List.of(
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "findPageWithTags",
                                ProblemOrder.class,
                                "select o from ProblemOrder o left join fetch o.tags where o.status = :status",
                                false,
                                true,
                                List.of(Status.class)),
                        new HibernateRepositoryMethodModel(
                                "com.example.ProblemOrderRepository",
                                "findByIds",
                                ProblemOrder.class,
                                "select o from ProblemOrder o where o.id in :ids",
                                false,
                                false,
                                List.of(List.class))));
        HibernateAdvisorScanner scanner = scanner(
                environment,
                List.of(repository),
                ProblemOrder.class,
                TableIdentifierEntity.class,
                SequenceEntity.class,
                TablePerClassEntity.class,
                VersionlessEntity.class,
                CacheableEntity.class);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.rulesEvaluated()).isEqualTo(29);
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
                        "HIB-FETCH-003",
                        "HIB-FETCH-004",
                        "HIB-ID-002",
                        "HIB-ID-003",
                        "HIB-MAP-004",
                        "HIB-MAP-005",
                        "HIB-MAP-006",
                        "HIB-MAP-007",
                        "HIB-MAP-008",
                        "HIB-MAP-009",
                        "HIB-ENTITY-002");
    }

    @Test
    void scanPassesWhenMappingsAndConfigurationAreSafe() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.open-in-view", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create")
                .withProperty("hibernate.default_batch_fetch_size", "32")
                .withProperty("hibernate.jdbc.batch_size", "25")
                .withProperty("hibernate.order_inserts", "true")
                .withProperty("hibernate.order_updates", "true")
                .withProperty("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", "25")
                .withProperty("hibernate.generate_statistics", "true")
                .withProperty("hibernate.connection.provider_disables_autocommit", "true")
                .withProperty("hibernate.query.in_clause_parameter_padding", "true");
        environment.setActiveProfiles("test");
        HibernateAdvisorScanner scanner = scanner(environment, SafeOrder.class);

        HibernateAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
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
                CLOCK);
    }

    @Entity
    static class ProblemOrder {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ManyToOne(cascade = CascadeType.REMOVE)
        Customer customer;

        @OneToMany
        List<LineItem> lineItems;

        @ManyToMany(cascade = CascadeType.ALL)
        List<Tag> tags;

        @OneToOne
        Profile profile;

        @ManyToOne(fetch = FetchType.LAZY)
        @NotFound(action = NotFoundAction.IGNORE)
        Customer legacyCustomer;

        Optional<String> notes;

        Status status;
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
    }

    @Entity
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
