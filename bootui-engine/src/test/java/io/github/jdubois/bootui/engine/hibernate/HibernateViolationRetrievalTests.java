package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleRefusals;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HibernateViolationRetrievalTests {
    @Test
    void aCatalogueRuleWithoutFindingsIsToldApartFromAnUnknownRule() {
        List<HibernatePersistenceUnitObservation> units = List.of(unit("orders", 0));
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(units, HibernateApplicationFacts.unknown(List.of()), List.of()),
                Clock.systemUTC(),
                List.of(new BulkUpdateVersionRule(), new SingleUnitPassingRule()));
        HibernateReport report = scanner.scan();
        AdvisorRuleRefusals.assertKnownAndUnknownRulesAreToldApart(
                List.of("HIB-QUERY-008", SingleUnitPassingRule.ID),
                report.results().stream().map(evaluated -> evaluated.id()).toList(),
                report.violationDetails().scanId(),
                (asked, scan) -> scanner.ruleViolations(asked, scan, 0, 1));
    }

    @Test
    void allTwentyTwoBulkUpdatesSurvivePerUnitAndAggregateSampling() {
        List<HibernatePersistenceUnitObservation> units = List.of(unit("orders", 0), unit("archive", 11));
        AtomicInteger observations = new AtomicInteger();
        HibernateScanner scanner = new HibernateScanner(
                () -> {
                    observations.incrementAndGet();
                    return new HibernateAdvisorObservation(
                            units, HibernateApplicationFacts.unknown(List.of()), List.of());
                },
                Clock.systemUTC(),
                List.of(new BulkUpdateVersionRule()));
        HibernateReport report = scanner.scan();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("HIB-QUERY-008");
            assertThat(result.violationCount()).isEqualTo(22);
            assertThat(result.sampleViolations()).hasSize(10);
        });
        String scanId = report.violationDetails().scanId();
        List<String> all = new ArrayList<>();
        for (int offset = 0; offset < 22; offset += 5) {
            var page = scanner.ruleViolations("HIB-QUERY-008", scanId, offset, 5);
            assertThat(page.violationCount()).isEqualTo(22);
            assertThat(page.retainedCount()).isEqualTo(22);
            assertThat(page.truncated()).isFalse();
            all.addAll(page.violations());
        }
        List<String> expected = units.stream()
                .flatMap(unit -> unit.repositories().get(0).methods().stream()
                        .map(method -> "[" + unit.label() + "] " + method.description()
                                + " performs a bulk UPDATE on versioned entity " + VersionedOrder.class.getName()
                                + " without advancing its version attribute."))
                .map(HibernateRuleSupport::detail)
                .toList();
        assertThat(all).containsExactlyElementsOf(expected);
        assertThat(all.subList(0, 10))
                .containsExactlyElementsOf(report.results().get(0).sampleViolations());
        assertThat(observations).hasValue(1);
        assertThat(scanner.applyDismissals(report, Set.of("HIB-QUERY-008")).violationDetails())
                .isEqualTo(report.violationDetails());
    }

    @Test
    void retainedIndexLimitDoesNotChangeHibernateCountsOrUnitPreview() {
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        List.of(unit("orders", 0), unit("archive", 11)),
                        HibernateApplicationFacts.unknown(List.of()),
                        List.of()),
                Clock.systemUTC(),
                List.of(new BulkUpdateVersionRule()));
        scanner.setViolationRetentionLimit(() -> 15);
        HibernateReport report = scanner.scan();
        var page = scanner.ruleViolations(
                "HIB-QUERY-008", report.violationDetails().scanId(), 0, 100);
        assertThat(page.violationCount()).isEqualTo(22);
        assertThat(page.violations()).hasSize(15);
        assertThat(page.truncated()).isTrue();
        assertThat(page.violations().get(11)).startsWith("[archive] OrderRepository#update11");
        assertThat(report.results().get(0).sampleViolations()).hasSize(10);
        assertThat(report.violationDetails().retained()).isEqualTo(15);
    }

    private static HibernatePersistenceUnitObservation unit(String label, int start) {
        List<HibernateRepositoryMethodModel> methods = IntStream.range(start, start + 11)
                .mapToObj(index -> new HibernateRepositoryMethodModel(
                        "OrderRepository",
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
                                true, true, false, false, false, int.class, false, null, null, List.of())))
                .toList();
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(VersionedOrder.class)),
                List.of(new HibernateRepositoryModel("OrderRepository", VersionedOrder.class, methods)),
                "7.4.5.Final",
                HibernateFactorySettings.unknown(),
                false);
    }

    @Entity
    static class VersionedOrder {
        @Id
        Long id;

        @Version
        Long version;

        int amount;
    }

    /** A rule that always passes, so a scan has a catalogue rule without findings. */
    private static final class SingleUnitPassingRule extends AbstractHibernateRule {
        static final String ID = "HIB-TEST-PASS";

        SingleUnitPassingRule() {
            super(new HibernateRuleDefinition(
                    ID, "Always passes", HibernateCategory.QUERY, "LOW", "Test rule.", "Nothing to do.", ""));
        }

        @Override
        HibernateRuleResultDto evaluateRule(HibernateContext context) {
            return HibernateRuleSupport.pass(definition());
        }
    }
}
