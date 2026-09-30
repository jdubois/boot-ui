package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorLocations;
import io.github.jdubois.bootui.engine.hibernate.kotlinfixtures.KotlinJvmFieldEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Hibernate findings about one entity, mapped attribute, or repository method carry that element's location at
 * member (or class) precision; the metamodel and reflection carry no line, so none is ever invented.
 */
class HibernateViolationLocationTests {

    private static final String THIS_FILE = "HibernateViolationLocationTests.java";

    private static HibernateScanner scanner(
            List<HibernatePersistenceUnitObservation> units, List<HibernateRule> rules) {
        return new HibernateScanner(
                () -> new HibernateAdvisorObservation(units, HibernateApplicationFacts.unknown(List.of()), List.of()),
                Clock.systemUTC(),
                rules);
    }

    private static HibernateRuleResultDto result(HibernateReport report, String id) {
        return report.results().stream()
                .filter(result -> id.equals(result.id()))
                .findFirst()
                .orElseThrow();
    }

    private static List<AdvisorRuleViolationsDto> pages(HibernateScanner scanner, String id, String scanId) {
        List<AdvisorRuleViolationsDto> pages = new ArrayList<>();
        int offset = 0;
        while (true) {
            AdvisorRuleViolationsDto page = scanner.ruleViolations(id, scanId, offset, 4);
            pages.add(page);
            if (!page.page().hasMore()) return pages;
            offset += page.page().returned();
        }
    }

    @Test
    void entityAttributeAndRepositoryFindingsCarryTheirJavaElement() {
        HibernatePersistenceUnitObservation unit = unit("orders", 0, Customer.class, PurchaseOrder.class);
        HibernateReport report = scanner(
                        List.of(unit),
                        List.of(
                                new PublicPersistentFieldRule(),
                                new EagerFetchRule(),
                                new MissingVersionRule(),
                                new BulkUpdateVersionRule()))
                .scan();

        AdvisorViolationLocationDto field =
                result(report, "HIB-ENTITY-005").sampleLocations().get(0);
        assertThat(field.className()).isEqualTo(Customer.class.getName());
        assertThat(field.memberName()).isEqualTo("nickname");
        assertThat(field.kind()).isEqualTo(AdvisorViolationLocationDto.FIELD);
        assertThat(field.sourceFile()).isEqualTo(THIS_FILE);
        assertThat(field.line()).isNull();
        assertThat(field.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_MEMBER);
        assertThat(Path.of(field.sourcePath()).getFileName()).hasToString(THIS_FILE);

        AdvisorViolationLocationDto eager =
                result(report, "HIB-FETCH-001").sampleLocations().get(0);
        assertThat(eager.className()).isEqualTo(PurchaseOrder.class.getName());
        assertThat(eager.memberName()).isEqualTo("customer");
        assertThat(eager.kind()).isEqualTo(AdvisorViolationLocationDto.FIELD);

        AdvisorViolationLocationDto entity =
                result(report, "HIB-ENTITY-008").sampleLocations().get(0);
        assertThat(entity.memberName()).isNull();
        assertThat(entity.kind()).isEqualTo(AdvisorViolationLocationDto.CLASS);
        assertThat(entity.precision()).isEqualTo(AdvisorViolationLocationDto.PRECISION_CLASS);
        assertThat(entity.sourceFile()).isEqualTo(THIS_FILE);

        HibernateRuleResultDto bulk = result(report, "HIB-QUERY-008");
        assertThat(bulk.sampleLocations())
                .hasSize(bulk.sampleViolations().size())
                .allSatisfy(location -> {
                    assertThat(location.kind()).isEqualTo(AdvisorViolationLocationDto.METHOD);
                    assertThat(location.sourceFile()).isEqualTo(THIS_FILE);
                });
        for (int index = 0; index < bulk.sampleViolations().size(); index++) {
            AdvisorViolationLocationDto location = bulk.sampleLocations().get(index);
            // The text names the repository; the location names the interface that declares the method.
            assertThat(bulk.sampleViolations().get(index))
                    .startsWith("[orders] " + OrderRepository.class.getName() + "#" + location.memberName() + " ");
            int number = Integer.parseInt(location.memberName().substring("update".length()));
            assertThat(location.className())
                    .isEqualTo((number % 2 == 0 ? OrderRepository.class : BaseRepository.class).getName());
        }

        assertThat(report.violationDetails().locationNotes()).isEmpty();
    }

    @Test
    void unitLabelledSamplesAndPagesStayAlignedAcrossUnitsTruncationAndDismissal() {
        List<HibernatePersistenceUnitObservation> units =
                List.of(unit("orders", 0, PurchaseOrder.class), unit("archive", 7, PurchaseOrder.class));
        HibernateScanner scanner = scanner(units, List.of(new BulkUpdateVersionRule()));
        scanner.setViolationRetentionLimit(() -> 11);
        HibernateReport report = scanner.scan();
        HibernateRuleResultDto bulk = result(report, "HIB-QUERY-008");
        assertThat(bulk.violationCount()).isEqualTo(14);
        assertThat(bulk.sampleViolations()).hasSize(10);
        assertThat(bulk.sampleLocations()).hasSize(10);
        assertThat(bulk.sampleViolations().get(7)).startsWith("[archive] ");

        List<String> texts = new ArrayList<>();
        List<AdvisorViolationLocationDto> locations = new ArrayList<>();
        for (AdvisorRuleViolationsDto page :
                pages(scanner, "HIB-QUERY-008", report.violationDetails().scanId())) {
            assertThat(page.locations()).hasSameSizeAs(page.violations());
            texts.addAll(page.violations());
            locations.addAll(page.locations());
        }
        assertThat(texts).hasSize(11);
        assertThat(texts.subList(0, 10)).containsExactlyElementsOf(bulk.sampleViolations());
        assertThat(locations.subList(0, 10)).containsExactlyElementsOf(bulk.sampleLocations());
        for (int index = 0; index < texts.size(); index++) {
            assertThat(texts.get(index)).contains("#" + locations.get(index).memberName() + " ");
        }

        HibernateRuleResultDto dismissed = scanner.applyDismissals(report, Set.of("HIB-QUERY-008"))
                .results()
                .get(0);
        assertThat(dismissed.dismissed()).isTrue();
        assertThat(dismissed.sampleLocations()).containsExactlyElementsOf(bulk.sampleLocations());
    }

    @Test
    void aRepositoryMethodWithoutAKnownDeclaringTypeKeepsItsTextButNoLocation() {
        HibernateRepositoryMethodModel method = new HibernateRepositoryMethodModel(
                OrderRepository.class.getName(),
                "update0",
                VersionedOrder.class,
                int.class,
                "update VersionedOrder e set e.amount = 1",
                false,
                null,
                false,
                true,
                false,
                false,
                List.of(),
                new HibernateQueryEvidence(true, true, false, false, false, int.class, false, null, null, List.of()));
        HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                "orders",
                "orders",
                List.of(HibernateEntityModel.fromClass(VersionedOrder.class)),
                List.of(new HibernateRepositoryModel(
                        OrderRepository.class.getName(), VersionedOrder.class, List.of(method))),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
        HibernateRuleResultDto bulk = result(
                scanner(List.of(unit), List.of(new BulkUpdateVersionRule())).scan(), "HIB-QUERY-008");
        assertThat(bulk.sampleViolations()).hasSize(1);
        assertThat(bulk.sampleLocations()).isEmpty();
    }

    @Test
    void kotlinEntitiesReportTheirKotlinSourceFile() {
        HibernatePersistenceUnitObservation unit = new HibernatePersistenceUnitObservation(
                "kotlin",
                "kotlin",
                List.of(HibernateEntityModel.fromClass(KotlinJvmFieldEntity.class)),
                List.of(),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
        HibernateReport report =
                scanner(List.of(unit), List.of(new PublicPersistentFieldRule())).scan();
        AdvisorViolationLocationDto location =
                result(report, "HIB-ENTITY-005").sampleLocations().get(0);
        assertThat(location.className()).isEqualTo(KotlinJvmFieldEntity.class.getName());
        assertThat(location.memberName()).isEqualTo("code");
        assertThat(location.sourceFile()).isEqualTo("KotlinHibernateFixtures.kt");
        assertThat(Path.of(location.sourcePath()).getFileName()).hasToString("KotlinHibernateFixtures.kt");
    }

    @Test
    void sourcePathsAreResolvedOnceDuringTheScanAndNeverOnDetailReads() {
        AtomicInteger resolutions = new AtomicInteger();
        HibernateScanner scanner =
                scanner(List.of(unit("orders", 0, PurchaseOrder.class)), List.of(new BulkUpdateVersionRule()));
        scanner.setSourceLocations((observation, located) -> {
            resolutions.incrementAndGet();
            return new AdvisorLocations.Resolution(location -> location, List.of("A note."));
        });
        HibernateReport report = scanner.scan();
        scanner.ruleViolations("HIB-QUERY-008", report.violationDetails().scanId(), 0, 100);
        scanner.lastReport();
        assertThat(resolutions).hasValue(1);
        assertThat(report.violationDetails().locationNotes()).containsExactly("A note.");
    }

    private static HibernatePersistenceUnitObservation unit(String label, int start, Class<?>... entities) {
        List<HibernateRepositoryMethodModel> methods = IntStream.range(start, start + 7)
                .mapToObj(index -> new HibernateRepositoryMethodModel(
                        OrderRepository.class.getName(),
                        "update" + index,
                        VersionedOrder.class,
                        int.class,
                        "update VersionedOrder e set e.amount = 1",
                        false,
                        null,
                        false,
                        true,
                        false,
                        false,
                        List.of(),
                        new HibernateQueryEvidence(
                                true, true, false, false, false, int.class, false, null, null, List.of()),
                        index % 2 == 0 ? OrderRepository.class : BaseRepository.class))
                .toList();
        List<HibernateEntityModel> models = new ArrayList<>();
        models.add(HibernateEntityModel.fromClass(VersionedOrder.class));
        for (Class<?> entity : entities) models.add(HibernateEntityModel.fromClass(entity));
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                models,
                List.of(new HibernateRepositoryModel(OrderRepository.class.getName(), VersionedOrder.class, methods)),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
    }

    interface BaseRepository {}

    interface OrderRepository extends BaseRepository {}

    @Entity
    static class VersionedOrder {
        @Id
        Long id;

        @Version
        Long version;

        int amount;
    }

    @Entity
    static class Customer {
        @Id
        Long id;

        @Version
        Long version;

        public String nickname;
    }

    @Entity
    static class PurchaseOrder {
        @Id
        Long id;

        @ManyToOne(fetch = FetchType.EAGER)
        Customer customer;

        String reference;
    }
}
