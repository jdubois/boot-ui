package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorSuppressedFindingDto;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorSuppression;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HibernateFindingSuppressionTests {

    private static final String RULE = "HIB-QUERY-008";

    @Test
    void dismissingOneFindingLowersTheActiveCountWithoutTouchingTheScan() {
        HibernateScanner scanner = scanner(15 + 7);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        List<String> identities = identities(scanner, scanId);
        String dismissedText =
                scanner.ruleViolations(RULE, scanId, 0, 100).violations().get(2);

        HibernateReport report = scanner.dismissedReport(Set.of(), List.of(finding(identities.get(2))));

        HibernateRuleResultDto result = report.results().get(0);
        assertThat(result.violationCount()).isEqualTo(21);
        assertThat(result.suppressedCount()).isEqualTo(1);
        assertThat(result.dismissed()).isFalse();
        assertThat(result.sampleViolations()).hasSize(10).doesNotContain(dismissedText);
        assertThat(report.violationsFound()).isEqualTo(1);
        assertThat(report.severityCounts())
                .filteredOn(count -> count.count() > 0)
                .singleElement()
                .satisfies(count -> assertThat(count.count()).isEqualTo(21));
        assertThat(report.suppression().suppressedFindings()).isEqualTo(1);
        assertThat(report.suppression().incompleteRules()).isEmpty();
        assertThat(report.suppression().findings())
                .containsExactly(new AdvisorSuppressedFindingDto(RULE, identities.get(2), AdvisorSuppression.MATCHED));

        // The scan itself is untouched: same retained findings, same details, same comparison.
        assertThat(report.violationDetails()).isEqualTo(raw.violationDetails());
        AdvisorRuleViolationsDto page =
                scanner.ruleViolations(RULE, scanId, 0, 100, List.of(finding(identities.get(2))));
        assertThat(page.violationCount()).isEqualTo(22);
        assertThat(page.suppressedCount()).isEqualTo(1);
        assertThat(page.retainedCount()).isEqualTo(22);
        assertThat(page.suppressed()).hasSize(22);
        assertThat(page.suppressed().get(2)).isTrue();
        assertThat(page.suppressed().stream().filter(Boolean::booleanValue)).hasSize(1);
        assertThat(page.violations()).hasSize(22).contains(dismissedText);
    }

    @Test
    void aPageWithNothingDismissedCarriesNoFlagsAndRestoringNeedsNoRescan() {
        HibernateScanner scanner = scanner(100);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        List<String> identities = identities(scanner, scanId);

        assertThat(scanner.ruleViolations(RULE, scanId, 0, 100, List.of()).suppressed())
                .isEmpty();
        assertThat(scanner.dismissedReport(Set.of(), List.of(finding(identities.get(0))))
                        .results()
                        .get(0)
                        .violationCount())
                .isEqualTo(21);
        // Restoring is the absence of the entry: the next read is the scan's own report again.
        assertThat(scanner.dismissedReport(Set.of(), List.of())).isEqualTo(raw);
    }

    @Test
    void aRuleWithEveryFindingDismissedStopsCountingButStaysListedToRestore() {
        HibernateScanner scanner = scanner(100);
        String scanId = scanner.scan().violationDetails().scanId();
        List<DismissedFindingDto> all = identities(scanner, scanId).stream()
                .map(HibernateFindingSuppressionTests::finding)
                .toList();

        HibernateReport report = scanner.dismissedReport(Set.of(), all);

        assertThat(report.violationsFound()).isZero();
        assertThat(report.severityCounts())
                .allSatisfy(count -> assertThat(count.count()).isZero());
        assertThat(report.scan().violationsFound()).isZero();
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.violationCount()).isZero();
            assertThat(result.suppressedCount()).isEqualTo(22);
            assertThat(result.sampleViolations()).isEmpty();
            assertThat(result.dismissed()).isFalse();
        });
    }

    @Test
    void ruleLevelDismissalTakesPrecedenceAndSuppressesNothing() {
        HibernateScanner scanner = scanner(100);
        String scanId = scanner.scan().violationDetails().scanId();
        List<String> identities = identities(scanner, scanId);

        HibernateReport report = scanner.dismissedReport(Set.of(RULE), List.of(finding(identities.get(0))));

        assertThat(report.results().get(0).dismissed()).isTrue();
        assertThat(report.results().get(0).suppressedCount()).isZero();
        assertThat(report.results().get(0).violationCount()).isEqualTo(22);
        assertThat(report.violationsFound()).isZero();
        assertThat(report.suppression().suppressedFindings()).isZero();
    }

    @Test
    void aTruncatedRuleMatchesOnlyWhatItRetainedAndSaysSoOnlyWhenItIsAffected() {
        HibernateScanner scanner = scanner(15);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        List<String> retained = identities(scanner, scanId);
        assertThat(retained).hasSize(15);
        String unretained = "v1:unit=archive|entity=com.acme.Unretained|member=x";

        HibernateReport untouched =
                scanner.dismissedReport(Set.of(), List.of(new DismissedFindingDto("HIB-ID-001", unretained, null)));
        assertThat(untouched.suppression().incompleteRules()).isEmpty();
        assertThat(untouched.results().get(0).violationCount()).isEqualTo(22);

        HibernateReport report =
                scanner.dismissedReport(Set.of(), List.of(finding(retained.get(1)), finding(unretained)));

        assertThat(report.results().get(0).violationCount()).isEqualTo(21);
        assertThat(report.results().get(0).suppressedCount()).isEqualTo(1);
        assertThat(report.suppression().incompleteRules()).containsExactly(RULE);
        assertThat(report.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::state)
                .containsExactly(AdvisorSuppression.MATCHED, AdvisorSuppression.UNVERIFIED);
    }

    @Test
    void thePreviewOfARuleThatRetainedLessThanItSamplesKeepsItsUnmatchedSamples() {
        HibernateScanner scanner = scanner(1);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        List<String> retained = identities(scanner, scanId);
        assertThat(retained).hasSize(1);
        String firstSample = raw.results().get(0).sampleViolations().get(0);

        HibernateReport report = scanner.dismissedReport(Set.of(), List.of(finding(retained.get(0))));

        assertThat(report.results().get(0).sampleViolations())
                .hasSize(9)
                .doesNotContain(firstSample)
                .containsExactlyElementsOf(
                        raw.results().get(0).sampleViolations().subList(1, 10));
        assertThat(report.results().get(0).violationCount()).isEqualTo(21);
    }

    @Test
    void aDismissedFindingTheScanNoLongerReportsIsOrphanedOnlyWhenTheRuleConcludedItsScope() {
        HibernateScanner scanner = scanner(100);
        String scanId = scanner.scan().violationDetails().scanId();
        String gone = "v1:unit=orders|entity=com.acme.Gone|member=x";

        HibernateReport report = scanner.dismissedReport(
                Set.of(),
                List.of(
                        finding(gone),
                        new DismissedFindingDto("HIB-RETIRED-001", gone, null),
                        new DismissedFindingDto("DB-SCHEMA-001", gone, null),
                        finding("v1:unit=elsewhere|entity=com.acme.Gone|member=x")));

        assertThat(report.violationDetails().scanId()).isEqualTo(scanId);
        assertThat(report.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::ruleId, AdvisorSuppressedFindingDto::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RULE, AdvisorSuppression.ORPHANED),
                        org.assertj.core.groups.Tuple.tuple("HIB-RETIRED-001", AdvisorSuppression.UNKNOWN_RULE),
                        org.assertj.core.groups.Tuple.tuple(RULE, AdvisorSuppression.UNVERIFIED));
        // Another advisor's dismissed finding is not this advisor's to list.
        assertThat(report.suppression().findings())
                .noneMatch(listed -> listed.ruleId().startsWith("DB-"));
        assertThat(report.results().get(0).suppressedCount()).isZero();
    }

    @Test
    void beforeAnyScanTheDismissedFindingsAreListedUnverified() {
        HibernateScanner scanner = scanner(100);

        HibernateReport report =
                scanner.dismissedReport(Set.of(), List.of(finding("v1:unit=orders|entity=com.acme.Order|member=x")));

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::state)
                .containsExactly(AdvisorSuppression.UNVERIFIED);
        assertThat(report.suppression().suppressedFindings()).isZero();
    }

    @Test
    void aReportFromAnEarlierScanIsNeverProjectedFromALaterScansFindings() {
        HibernateScanner scanner = scanner(100);
        HibernateReport first = scanner.scan();
        List<String> identities = identities(scanner, first.violationDetails().scanId());
        scanner.scan();

        HibernateReport report = scanner.applyDismissals(first, Set.of(), List.of(finding(identities.get(0))));

        assertThat(report.results().get(0).violationCount()).isEqualTo(22);
        assertThat(report.results().get(0).suppressedCount()).isZero();
        assertThat(report.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::state)
                .containsExactly(AdvisorSuppression.UNVERIFIED);
    }

    @Test
    void theComparisonWithThePreviousScanIsIdenticalBeforeAndAfterADismissal() {
        HibernateScanner scanner = scanner(100);
        scanner.scan();
        HibernateReport second = scanner.scan();
        List<String> identities = identities(scanner, second.violationDetails().scanId());
        var changesBefore = scanner.lastReport().violationDetails().changes();
        var pageBefore = scanner.ruleViolations(RULE, second.violationDetails().scanId(), 0, 100);

        HibernateReport dismissed = scanner.dismissedReport(Set.of(), List.of(finding(identities.get(0))));

        assertThat(dismissed.violationDetails().changes()).isEqualTo(changesBefore);
        assertThat(scanner.lastReport()).isEqualTo(second);
        assertThat(scanner.ruleViolations(RULE, second.violationDetails().scanId(), 0, 100))
                .isEqualTo(pageBefore);
    }

    private static DismissedFindingDto finding(String identity) {
        return new DismissedFindingDto(RULE, identity, null);
    }

    private static List<String> identities(HibernateScanner scanner, String scanId) {
        return scanner.ruleViolations(RULE, scanId, 0, 100).identities();
    }

    private static HibernateScanner scanner(int retention) {
        List<HibernatePersistenceUnitObservation> units = List.of(unit("orders", 0), unit("archive", 11));
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(units, HibernateApplicationFacts.unknown(List.of()), List.of()),
                Clock.systemUTC(),
                List.of(new BulkUpdateVersionRule()));
        scanner.setViolationRetentionLimit(() -> retention);
        return scanner;
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
}
