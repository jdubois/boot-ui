package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorSuppressedFindingDto;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateSeverityCountDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorSuppression;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings.SchemaAction;
import io.github.jdubois.bootui.engine.mcp.McpScanSummaries;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HibernateFindingSuppressionTests {

    private static final String RULE = "HIB-QUERY-008";
    private static final String DDL_RULE = "HIB-CONFIG-002";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ddlDismissalControlsPreserveWorstActiveGradeAndRawSnapshot(boolean criticalFirst) {
        HibernateScanner scanner = ddlScanner(ddlUnits(criticalFirst), 100);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        var page = scanner.ruleViolations(DDL_RULE, scanId, 0, 100);

        assertThat(raw.scan().status()).isEqualTo("SCANNED");
        assertThat(raw.evidence().usable()).isTrue();
        assertThat(raw.evidence().coverageComplete()).isTrue();
        assertDdlGrade(raw, "CRITICAL", 2);
        assertThat(page.identities())
                .containsExactlyElementsOf(ddlUnits(criticalFirst).stream()
                        .map(unit -> ddlFinding(unit.label()).identity())
                        .toList());
        assertThat(page.violationCount()).isEqualTo(2);
        assertThat(page.retainedCount()).isEqualTo(2);

        HibernateReport highDismissed = scanner.dismissedReport(Set.of(), List.of(ddlFinding("archive")));
        assertDdlGrade(highDismissed, "CRITICAL", 1);
        assertThat(highDismissed.results().get(0).suppressedCount()).isEqualTo(1);
        assertThat(highDismissed.results().get(0).sampleViolations())
                .singleElement()
                .asString()
                .startsWith("[orders]");

        HibernateReport allDismissed =
                scanner.dismissedReport(Set.of(), List.of(ddlFinding("orders"), ddlFinding("archive")));
        assertThat(allDismissed.violationsFound()).isZero();
        assertThat(allDismissed.scan().violationsFound()).isZero();
        assertThat(allDismissed.results().get(0).violationCount()).isZero();
        assertThat(allDismissed.results().get(0).suppressedCount()).isEqualTo(2);
        assertThat(allDismissed.results().get(0).severity()).isEqualTo("CRITICAL");
        assertThat(allDismissed.severityCounts())
                .allSatisfy(count -> assertThat(count.count()).isZero());
        assertThat(allDismissed.violationDetails()).isEqualTo(raw.violationDetails());
        assertThat(scanner.dismissedReport(Set.of(), List.of())).isEqualTo(raw);
        assertThat(scanner.lastReport()).isEqualTo(raw);
        assertThat(scanner.ruleViolations(DDL_RULE, scanId, 0, 100)).isEqualTo(page);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dismissingDestructiveDdlMatchesTheIndependentlyScannedRemainingUnit(boolean criticalFirst) {
        HibernateScanner scanner = ddlScanner(ddlUnits(criticalFirst), 100);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        var pageBefore = scanner.ruleViolations(DDL_RULE, scanId, 0, 100);
        HibernateReport archiveOnly = ddlScanner(List.of(ddlUnit("archive", SchemaAction.UPDATE)), 100)
                .scan();
        assertDdlGrade(archiveOnly, "HIGH", 1);

        HibernateReport projected = scanner.dismissedReport(Set.of(), List.of(ddlFinding("orders")));

        assertThat(projected.violationsFound()).isEqualTo(1);
        assertThat(projected.scan().violationsFound()).isEqualTo(1);
        assertThat(projected.results().get(0).violationCount()).isEqualTo(1);
        assertThat(projected.results().get(0).suppressedCount()).isEqualTo(1);
        assertThat(projected.results().get(0).sampleViolations())
                .isEqualTo(archiveOnly.results().get(0).sampleViolations());
        assertThat(projected.suppression().findings())
                .containsExactly(new AdvisorSuppressedFindingDto(
                        DDL_RULE, ddlFinding("orders").identity(), AdvisorSuppression.MATCHED));
        assertThat(projected.suppression().incompleteRules()).isEmpty();
        assertThat(projected.violationDetails()).isEqualTo(raw.violationDetails());
        assertThat(projected.evidence()).isEqualTo(raw.evidence());
        var page = scanner.ruleViolations(DDL_RULE, scanId, 0, 100, List.of(ddlFinding("orders")));
        assertThat(page.violationCount()).isEqualTo(2);
        assertThat(page.retainedCount()).isEqualTo(2);
        assertThat(page.suppressedCount()).isEqualTo(1);
        assertThat(page.identities()).isEqualTo(pageBefore.identities());
        assertThat(page.violations()).isEqualTo(pageBefore.violations());
        assertThat(page.suppressed())
                .containsExactlyElementsOf(page.identities().stream()
                        .map(identity -> identity.equals(ddlFinding("orders").identity()))
                        .toList());
        assertThat(scanner.ruleViolations(DDL_RULE, scanId, 0, 1).identities())
                .containsExactly(pageBefore.identities().get(0));
        assertThat(scanner.ruleViolations(DDL_RULE, scanId, 1, 1).identities())
                .containsExactly(pageBefore.identities().get(1));
        assertThat(scanner.dismissedReport(Set.of(), List.of())).isEqualTo(raw);
        assertThat(scanner.lastReport()).isEqualTo(raw);

        assertAll(
                () -> assertThat(projected.results().get(0).severity())
                        .isEqualTo(archiveOnly.results().get(0).severity()),
                () -> assertThat(projected.severityCounts()).isEqualTo(archiveOnly.severityCounts()),
                () -> assertThat(McpScanSummaries.hibernate(projected).get("severityCounts"))
                        .isEqualTo(McpScanSummaries.hibernate(archiveOnly).get("severityCounts")),
                () -> assertThat(McpScanSummaries.hibernate(projected).get("topFindings"))
                        .isEqualTo(McpScanSummaries.hibernate(archiveOnly).get("topFindings")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ddlDismissalsNeverRemoveAnIndependentUnknownUnit(boolean criticalFirst) {
        List<HibernatePersistenceUnitObservation> units = new java.util.ArrayList<>(ddlUnits(criticalFirst));
        units.add(ddlUnit("unread", SchemaAction.UNKNOWN));
        HibernateScanner scanner = ddlScanner(units, 100);
        HibernateReport raw = scanner.scan();

        assertThat(raw.scan().status()).isEqualTo("PARTIAL");
        assertThat(raw.evidence().usable()).isTrue();
        assertThat(raw.evidence().coverageComplete()).isFalse();
        assertThat(raw.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.unit()).isEqualTo("unread"));
        assertDdlGrade(raw, "CRITICAL", 2);
        for (List<DismissedFindingDto> findings :
                List.of(List.of(ddlFinding("orders")), List.of(ddlFinding("orders"), ddlFinding("archive")))) {
            HibernateReport projected = scanner.dismissedReport(Set.of(), findings);
            assertThat(projected.scan().status()).isEqualTo("PARTIAL");
            assertThat(projected.evidence()).isEqualTo(raw.evidence());
            assertThat(projected.diagnostics()).isEqualTo(raw.diagnostics());
            assertThat(projected.violationDetails()).isEqualTo(raw.violationDetails());
            assertThat(projected.results().get(0).coverageNote())
                    .isEqualTo(raw.results().get(0).coverageNote())
                    .contains("[unread]");
            assertThat(projected.results().get(0).violationCount()).isEqualTo(2 - findings.size());
            if (findings.size() == 1) assertDdlGrade(projected, "HIGH", 1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void incompleteDdlRetentionCannotJustifyLoweringTheGrade(boolean criticalFirst) {
        HibernateScanner scanner = ddlScanner(ddlUnits(criticalFirst), 1);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        var page = scanner.ruleViolations(DDL_RULE, scanId, 0, 100);
        assertThat(page.identities()).hasSize(1);
        String retainedUnit = criticalFirst ? "orders" : "archive";
        assertThat(page.identities()).containsExactly(ddlFinding(retainedUnit).identity());

        HibernateReport projected = scanner.dismissedReport(
                Set.of(), List.of(ddlFinding(retainedUnit), ddlFinding(criticalFirst ? "archive" : "orders")));

        assertDdlGrade(projected, "CRITICAL", 1);
        assertThat(projected.results().get(0).suppressedCount()).isEqualTo(1);
        assertThat(projected.suppression().incompleteRules()).containsExactly(DDL_RULE);
        assertThat(projected.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::state)
                .containsExactly(AdvisorSuppression.MATCHED, AdvisorSuppression.UNVERIFIED);
        assertThat(projected.violationDetails()).isEqualTo(raw.violationDetails());
        assertThat(projected.violationDetails().total()).isEqualTo(2);
        assertThat(projected.violationDetails().retained()).isEqualTo(1);
        assertThat(projected.violationDetails().truncated()).isTrue();
        assertThat(projected.evidence()).isEqualTo(raw.evidence());
        assertThat(scanner.ruleViolations(DDL_RULE, scanId, 0, 100)).isEqualTo(page);
    }

    @Test
    void eagerRuleDismissalsNeverMoveFromToOneToCollections() {
        HibernateScanner scanner = eagerScanner(100, false);
        HibernateReport raw = scanner.scan();
        String scanId = raw.violationDetails().scanId();
        var toOne = scanner.ruleViolations("HIB-FETCH-001", scanId, 0, 100);
        var collections = scanner.ruleViolations("HIB-FETCH-010", scanId, 0, 100);

        HibernateReport ruleDismissed = scanner.dismissedReport(Set.of("HIB-FETCH-001"), List.of());
        assertThat(eagerResult(ruleDismissed, "HIB-FETCH-001").dismissed()).isTrue();
        assertThat(eagerResult(ruleDismissed, "HIB-FETCH-010")).isEqualTo(eagerResult(raw, "HIB-FETCH-010"));
        assertThat(ruleDismissed.severityCounts())
                .filteredOn(count -> count.count() > 0)
                .singleElement()
                .satisfies(count -> {
                    assertThat(count.severity()).isEqualTo("HIGH");
                    assertThat(count.count()).isEqualTo(4);
                });

        // A pre-split collection dismissal is still keyed to 001, even though its member identity is unchanged.
        List<DismissedFindingDto> oldDismissals = List.of(
                new DismissedFindingDto("HIB-FETCH-001", toOne.identities().get(0), null),
                new DismissedFindingDto(
                        "HIB-FETCH-001", collections.identities().get(0), null));
        HibernateReport findingDismissed = scanner.dismissedReport(Set.of(), oldDismissals);
        assertThat(eagerResult(findingDismissed, "HIB-FETCH-001").violationCount())
                .isEqualTo(3);
        assertThat(eagerResult(findingDismissed, "HIB-FETCH-001").suppressedCount())
                .isEqualTo(1);
        assertThat(eagerResult(findingDismissed, "HIB-FETCH-010").violationCount())
                .isEqualTo(4);
        assertThat(eagerResult(findingDismissed, "HIB-FETCH-010").suppressedCount())
                .isZero();
        assertThat(findingDismissed.suppression().findings())
                .extracting(AdvisorSuppressedFindingDto::state)
                .containsExactly(AdvisorSuppression.MATCHED, AdvisorSuppression.ORPHANED);
        assertThat(scanner.ruleViolations("HIB-FETCH-010", scanId, 0, 100, oldDismissals))
                .isEqualTo(collections);
        assertThat(scanner.lastReport()).isEqualTo(raw);

        HibernateReport singleCollectionDismissed = scanner.dismissedReport(
                Set.of(),
                List.of(new DismissedFindingDto(
                        "HIB-FETCH-010", collections.identities().get(0), null)));
        assertThat(eagerResult(singleCollectionDismissed, "HIB-FETCH-001").violationCount())
                .isEqualTo(4);
        assertThat(eagerResult(singleCollectionDismissed, "HIB-FETCH-010").violationCount())
                .isEqualTo(3);
        assertThat(eagerResult(singleCollectionDismissed, "HIB-FETCH-010").suppressedCount())
                .isEqualTo(1);

        HibernateReport collectionDismissed = scanner.dismissedReport(Set.of("HIB-FETCH-010"), List.of());
        assertThat(eagerResult(collectionDismissed, "HIB-FETCH-001").dismissed())
                .isFalse();
        assertThat(eagerResult(collectionDismissed, "HIB-FETCH-010").dismissed())
                .isTrue();
        assertThat(scanner.dismissedReport(Set.of(), List.of())).isEqualTo(raw);

        HibernateReport rescanned = scanner.scan();
        assertThat(eagerResult(scanner.dismissedReport(Set.of("HIB-FETCH-001"), oldDismissals), "HIB-FETCH-010")
                        .violationCount())
                .isEqualTo(4);
        assertThat(rescanned.violationDetails().changes().newCount()).isZero();
        assertThat(rescanned.violationDetails().changes().resolvedCount()).isZero();
        assertThat(rescanned.violationDetails().changes().unchangedCount()).isEqualTo(8);
        assertThat(rescanned.violationDetails().changes().complete()).isTrue();
    }

    @Test
    void splitEagerRulesKeepInheritedMemberLocationsAndConcreteEntityAndUnitIdentities() {
        HibernateScanner scanner = eagerScanner(100, false);
        HibernateReport raw = scanner.scan();
        assertThat(raw.scan().status()).isEqualTo("SCANNED");
        assertThat(eagerResult(raw, "HIB-FETCH-001").severity()).isEqualTo("MEDIUM");
        assertThat(eagerResult(raw, "HIB-FETCH-010").severity()).isEqualTo("HIGH");

        for (String id : List.of("HIB-FETCH-001", "HIB-FETCH-010")) {
            String member = id.equals("HIB-FETCH-001") ? "parent" : "labels";
            var page = scanner.ruleViolations(id, raw.violationDetails().scanId(), 0, 100);
            assertThat(page.violationCount()).isEqualTo(4);
            assertThat(page.identities())
                    .containsExactly(
                            "v1:unit=orders|entity=" + EagerChildOne.class.getName() + "|member=" + member,
                            "v1:unit=orders|entity=" + EagerChildTwo.class.getName() + "|member=" + member,
                            "v1:unit=archive|entity=" + EagerChildOne.class.getName() + "|member=" + member,
                            "v1:unit=archive|entity=" + EagerChildTwo.class.getName() + "|member=" + member);
            assertThat(page.locations()).hasSize(4).allSatisfy(location -> {
                assertThat(location.className()).isEqualTo(EagerBase.class.getName());
                assertThat(location.memberName()).isEqualTo(member);
            });
            assertThat(page.violations()).allMatch(text -> text.contains(EagerBase.class.getName() + "#" + member));
            assertThat(page.violations().get(0)).startsWith("[orders]");
            assertThat(page.violations().get(2)).startsWith("[archive]");
        }
    }

    @Test
    void splitEagerRulesRetainRawCountsWhenDetailsAreTruncated() {
        HibernateScanner scanner = eagerScanner(3, false);
        HibernateReport raw = scanner.scan();

        assertThat(raw.evidence().coverageComplete()).isTrue();
        assertThat(raw.violationDetails().retained()).isEqualTo(3);
        for (String id : List.of("HIB-FETCH-001", "HIB-FETCH-010")) {
            var page = scanner.ruleViolations(id, raw.violationDetails().scanId(), 0, 100);
            assertThat(page.violationCount()).isEqualTo(4);
            assertThat(page.truncated()).isTrue();
            assertThat(eagerResult(raw, id).violationCount()).isEqualTo(4);
        }
    }

    @Test
    void unsupportedFetchDeclarationsKeepBothKnownEagerRulesPartialRatherThanPassing() {
        HibernateScanner scanner = eagerScanner(100, true);
        HibernateReport raw = scanner.scan();

        assertThat(raw.scan().status()).isEqualTo("PARTIAL");
        assertThat(raw.evidence().coverageComplete()).isFalse();
        assertThat(raw.diagnostics())
                .extracting(diagnostic -> diagnostic.source())
                .containsExactlyInAnyOrder("HIB-FETCH-001", "HIB-FETCH-010");
        for (String id : List.of("HIB-FETCH-001", "HIB-FETCH-010")) {
            assertThat(eagerResult(raw, id).violationCount()).isEqualTo(4);
            assertThat(eagerResult(raw, id).coverageNote()).contains("[archive]", "fetch declaration is unavailable");
        }
    }

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

    private static DismissedFindingDto ddlFinding(String unit) {
        return new DismissedFindingDto(DDL_RULE, "v1:unit=" + unit + "|setting=hibernate.hbm2ddl.auto", null);
    }

    private static void assertDdlGrade(HibernateReport report, String severity, int count) {
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(DDL_RULE);
            assertThat(result.severity()).isEqualTo(severity);
            assertThat(result.violationCount()).isEqualTo(count);
        });
        assertThat(report.severityCounts())
                .filteredOn(bucket -> bucket.count() > 0)
                .containsExactly(new HibernateSeverityCountDto(severity, count));
    }

    private static List<HibernatePersistenceUnitObservation> ddlUnits(boolean criticalFirst) {
        var critical = ddlUnit("orders", SchemaAction.DROP_AND_CREATE);
        var high = ddlUnit("archive", SchemaAction.UPDATE);
        return criticalFirst ? List.of(critical, high) : List.of(high, critical);
    }

    private static HibernateScanner ddlScanner(List<HibernatePersistenceUnitObservation> units, int retention) {
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(
                        units, HibernateApplicationFacts.unknown(List.of("prod")), List.of()),
                Clock.systemUTC(),
                List.of(new RiskyDdlAutoRule()));
        scanner.setViolationRetentionLimit(() -> retention);
        return scanner;
    }

    private static HibernatePersistenceUnitObservation ddlUnit(String label, SchemaAction action) {
        HibernateFactorySettings settings = new HibernateFactorySettings(
                null, null, null, null, null, null, null, null, null, null, action, null, null, null, null, null, null,
                null, null, null, null, null, null);
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(HibernateEntityModel.fromClass(VersionedOrder.class)),
                List.of(),
                "7.4.5.Final",
                settings,
                false);
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

    private static HibernateRuleResultDto eagerResult(HibernateReport report, String id) {
        return report.results().stream()
                .filter(result -> id.equals(result.id()))
                .findFirst()
                .orElseThrow();
    }

    private static HibernateScanner eagerScanner(int retention, boolean unknown) {
        List<HibernateEntityModel> entities = List.of(
                HibernateEntityModel.fromClass(EagerChildOne.class),
                HibernateEntityModel.fromClass(EagerChildTwo.class));
        List<HibernateEntityModel> archive = new java.util.ArrayList<>(entities);
        if (unknown) {
            archive.add(new HibernateEntityModel(
                    "XmlOwner",
                    null,
                    List.of(
                            new HibernateAttributeModel(
                                    "XmlOwner",
                                    "parent",
                                    Object.class,
                                    Object.class,
                                    "MANY_TO_ONE",
                                    false,
                                    true,
                                    List.of()),
                            new HibernateAttributeModel(
                                    "XmlOwner",
                                    "labels",
                                    Object.class,
                                    Object.class,
                                    "ELEMENT_COLLECTION",
                                    false,
                                    true,
                                    List.of()))));
        }
        List<HibernatePersistenceUnitObservation> units = List.of(
                new HibernatePersistenceUnitObservation(
                        "orders",
                        "orders",
                        entities,
                        List.of(),
                        "7.4.5.Final",
                        HibernateFactorySettings.unknown(),
                        false),
                new HibernatePersistenceUnitObservation(
                        "archive",
                        "archive",
                        archive,
                        List.of(),
                        "7.4.5.Final",
                        HibernateFactorySettings.unknown(),
                        false));
        HibernateScanner scanner = new HibernateScanner(
                () -> new HibernateAdvisorObservation(units, HibernateApplicationFacts.unknown(List.of()), List.of()),
                Clock.systemUTC(),
                List.of(new EagerFetchRule(), new EagerCollectionFetchRule()));
        scanner.setViolationRetentionLimit(() -> retention);
        return scanner;
    }

    @MappedSuperclass
    static class EagerBase {
        @ManyToOne
        VersionedOrder parent;

        @ElementCollection(fetch = FetchType.EAGER)
        Set<String> labels;
    }

    @Entity
    static class EagerChildOne extends EagerBase {
        @Id
        Long id;
    }

    @Entity
    static class EagerChildTwo extends EagerBase {
        @Id
        Long id;
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
