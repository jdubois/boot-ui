package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorReport;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
        assertThat(report.rulesEvaluated()).isEqualTo(9);
        assertThat(report.results())
                .extracting(result -> result.id())
                .containsExactly(
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
    void scanPassesWhenMappingsAndConfigurationAreSafe() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.jpa.open-in-view", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create")
                .withProperty("hibernate.default_batch_fetch_size", "32");
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
        return new HibernateAdvisorScanner(List.of(HibernateEntityModel.fromClass(entityType)), environment, CLOCK);
    }

    @Entity
    static class ProblemOrder {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ManyToOne
        Customer customer;

        @OneToMany
        List<LineItem> lineItems;

        @ManyToMany
        List<Tag> tags;

        Status status;
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
        List<LineItem> ownedLineItems;

        @ManyToMany
        Set<Tag> tags;

        @Enumerated(EnumType.STRING)
        Status status;
    }

    static class Customer {}

    static class LineItem {}

    static class Tag {}

    enum Status {
        NEW,
        SHIPPED
    }
}
