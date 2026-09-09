package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyAssessmentDto;
import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencySeverityCountDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyReportsTests {

    @Test
    void unknownAdvisoriesNeverEstablishEligibilityEvenAfterDismissal() {
        var dependency = vulnerableDependency("org.example", "lib", "1.0.0", "UNKNOWN");
        var report = DependencyReports.report(true, "PARTIAL", "Details incomplete", 1L, 1, List.of(dependency));
        var dismissed = DependencyReports.applyDismissals(
                report, Set.of(DependencyReports.dismissalKey("V-lib", "org.example:lib")));

        assertThat(report.evidence().usable()).isFalse();
        assertThat(dismissed.evidence()).isSameAs(report.evidence());
        assertThat(dismissed.dependencies().get(0).vulnerabilities().get(0).severity())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void fullyQueriedUnknownSeverityIsStillIncompleteBeforeAndAfterDismissal() {
        var report = assessedReport("UNKNOWN");
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations())
                .contains("Findings with unknown severity are excluded from score penalties.");
        var dismissed =
                DependencyReports.applyDismissals(report, Set.of(DependencyReports.dismissalKey("V-UNKNOWN", "g:a")));
        assertThat(dismissed.evidence()).isEqualTo(report.evidence());
        assertThat(dismissed.severityCounts()).allMatch(count -> count.count() == 0);
    }

    @Test
    void mixedKnownAndUnknownFindingsKeepOnlyKnownEligibilityWithoutCompletionCredit() {
        var report = assessedReport("HIGH", "NONE", "UNKNOWN");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        var dismissed = DependencyReports.applyDismissals(
                report,
                Set.of(
                        DependencyReports.dismissalKey("V-HIGH", "g:a"),
                        DependencyReports.dismissalKey("V-NONE", "g:a"),
                        DependencyReports.dismissalKey("V-UNKNOWN", "g:a")));
        assertThat(dismissed.evidence()).isEqualTo(report.evidence());
        assertThat(dismissed.severityCounts()).allMatch(count -> count.count() == 0);
        assertThat(DependencyReports.applyDismissals(dismissed, Set.of()).evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void fullyAssessedKnownFindingsAndGenuinelyEmptyQueriesEstablishCompletion() {
        for (String severity : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE")) {
            var report = assessedReport(severity);
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.evidence().coverageComplete()).isTrue();
            var dismissed = DependencyReports.applyDismissals(
                    report, Set.of(DependencyReports.dismissalKey("V-" + severity, "g:a")));
            assertThat(dismissed.evidence()).isEqualTo(report.evidence());
            assertThat(dismissed.severityCounts()).allMatch(count -> count.count() == 0);
        }
        var empty = assessedReport();
        assertThat(empty.evidence().usable()).isTrue();
        assertThat(empty.evidence().coverageComplete()).isTrue();
    }

    @Test
    void unknownOrFailedEmptyQueriesCannotEstablishCompletionAndLimitsRemainVisible() {
        for (var assessment : List.of(DependencyAssessmentDto.unknown(), new DependencyAssessmentDto(true, false))) {
            var dependency = new DependencyDto("g", "a", "1", "g:a", "test", 0, "NONE", List.of(), assessment);
            var report = DependencyReports.report(
                    true, "SCANNED", "done", 1L, 1, 0, List.of(dependency), DependencyCoverageDto.of(1, 0, List.of()));
            assertThat(report.evidence().usable()).isFalse();
            assertThat(report.evidence().coverageComplete()).isFalse();
        }
        var complete = assessedReport();
        var limited = DependencyReports.report(
                true, "SCANNED", "done", 1L, 1, 2, complete.dependencies(), complete.coverage());
        assertThat(limited.evidence().usable()).isTrue();
        assertThat(limited.evidence().coverageComplete()).isFalse();
        assertThat(limited.evidence().limitations())
                .contains("Some inventory packages were excluded by the scan limit.");
        var noScope = DependencyReports.report(
                true, "SCANNED", "done", 1L, 0, 0, List.of(), DependencyCoverageDto.of(0, 0, List.of()));
        assertThat(noScope.evidence().usable()).isFalse();
        assertThat(noScope.evidence().coverageComplete()).isTrue();
    }

    private static DependenciesReport assessedReport(String... severities) {
        var findings = Arrays.stream(severities)
                .map(severity -> vulnerability("V-" + severity, severity))
                .toList();
        var dependency = new DependencyDto(
                "g",
                "a",
                "1",
                "g:a",
                "test",
                findings.size(),
                DependencyReports.highestSeverity(findings),
                findings,
                new DependencyAssessmentDto(true, true));
        return DependencyReports.report(
                true, "SCANNED", "done", 1L, 1, 0, List.of(dependency), DependencyCoverageDto.of(1, 0, List.of()));
    }

    @Test
    void dismissalRestoreAndEpssPreserveEvidence() {
        var assessment = new io.github.jdubois.bootui.core.dto.DependencyAssessmentDto(true, true);
        var evidence =
                new io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto(true, false, List.of("Inventory incomplete"));
        DependencyDto dependency = new DependencyDto(
                "g", "a", "1", "g:a", "test", 1, "UNKNOWN", List.of(vulnerability("V-a", "UNKNOWN")), assessment);
        DependenciesReport report =
                new DependenciesReport(true, 1, 1, List.of(), null, null, List.of(dependency), evidence);
        DependenciesReport dismissed =
                DependencyReports.applyDismissals(report, Set.of(DependencyReports.dismissalKey("V-a", "g:a")));
        assertThat(dismissed.evidence()).isSameAs(evidence);
        assertThat(dismissed.dependencies().get(0).assessment()).isSameAs(assessment);
        assertThat(dismissed.dependencies().get(0).vulnerabilities()).hasSize(1);
        DependenciesReport restored = DependencyReports.applyDismissals(dismissed, Set.of());
        assertThat(restored.evidence()).isSameAs(evidence);
        assertThat(restored.dependencies().get(0).assessment()).isSameAs(assessment);
        assertThat(DependencyReports.applyEpssScores(restored.dependencies(), Map.of("CVE-1", new EpssScore(0.1, 0.2)))
                        .get(0)
                        .assessment())
                .isSameAs(assessment);
    }

    @Test
    void fixAvailabilityRequiresAPositivelyComparableNewerCandidate() {
        assertThat(DependencyReports.fixAvailable(null, List.of("2"))).isFalse();
        assertThat(DependencyReports.fixAvailable(" ", List.of("2"))).isFalse();
        assertThat(DependencyReports.fixAvailable("1", Arrays.asList(null, ""))).isFalse();
        assertThat(DependencyReports.fixAvailable("1", null)).isFalse();
        assertThat(DependencyReports.fixAvailable("1", List.of())).isFalse();
        assertThat(DependencyReports.fixAvailable("1.0", List.of("0.9", "1.0-final")))
                .isFalse();
        assertThat(DependencyReports.fixAvailable("1.0-rc1", List.of("1.0"))).isTrue();
        assertThat(DependencyReports.fixAvailable("1", Arrays.asList(null, "2")))
                .isTrue();
    }

    @Test
    void epssUsesMaximumAvailableProbabilityAndKeepsItsPairedPercentileRegardlessOfAliasOrder() {
        Map<String, EpssScore> scores = Map.of(
                "CVE-2024-1000", new EpssScore(0.1, 0.99),
                "CVE-2024-2000", new EpssScore(0.8, 0.9),
                "CVE-2024-3000", new EpssScore(0.5, 0.95));
        for (List<String> aliases : List.of(
                List.of("CVE-2024-1000", "CVE-2024-2000", "CVE-2024-3000"),
                List.of("CVE-2024-3000", "CVE-2024-2000", "CVE-2024-1000"))) {
            DependencyVulnerabilityDto result = epssFinding("GHSA-example", aliases, scores);

            assertThat(result.epssScore()).isEqualTo(0.8);
            assertThat(result.epssPercentile()).isEqualTo(0.9);
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.score()).isEqualTo(7.5);
            assertThat(result.dismissed()).isTrue();
        }
    }

    @Test
    void epssIncludesTheAdvisoryOwnCveAndDoesNotStopAtAMissingAlias() {
        Map<String, EpssScore> scores = Map.of(
                "CVE-2024-1000", new EpssScore(0.8, 0.9),
                "CVE-2024-3000", new EpssScore(0.2, 0.5));
        DependencyVulnerabilityDto own =
                epssFinding("CVE-2024-1000", List.of("CVE-2024-2000", "CVE-2024-3000"), scores);
        DependencyVulnerabilityDto later =
                epssFinding("GHSA-example", List.of("CVE-2024-2000", "CVE-2024-3000"), scores);

        assertThat(own.epssScore()).isEqualTo(0.8);
        assertThat(own.epssPercentile()).isEqualTo(0.9);
        assertThat(later.epssScore()).isEqualTo(0.2);
        assertThat(later.epssPercentile()).isEqualTo(0.5);
    }

    @Test
    void epssProbabilityTiesUseTheLexicallySmallestCveAndItsPercentile() {
        Map<String, EpssScore> scores = Map.of(
                "CVE-2024-1000", new EpssScore(0.5, 0.7),
                "CVE-2024-2000", new EpssScore(0.5, 0.8));
        for (String own : List.of("CVE-2024-1000", "CVE-2024-2000")) {
            DependencyVulnerabilityDto result = epssFinding(own, List.of("CVE-2024-2000", "CVE-2024-1000"), scores);

            assertThat(result.epssScore()).isEqualTo(0.5);
            assertThat(result.epssPercentile()).isEqualTo(0.7);
        }
    }

    @Test
    void epssRejectsInvalidProbabilityAndPercentileDefensivelyButKeepsOtherValidScores() {
        for (double invalid :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01}) {
            for (EpssScore invalidScore : List.of(new EpssScore(invalid, 0.9), new EpssScore(1, invalid))) {
                Map<String, EpssScore> scores =
                        Map.of("CVE-2024-1000", invalidScore, "CVE-2024-2000", new EpssScore(0.2, 0.5));
                DependencyVulnerabilityDto result = epssFinding("CVE-2024-1000", List.of("CVE-2024-2000"), scores);

                assertThat(result.epssScore()).isEqualTo(0.2);
                assertThat(result.epssPercentile()).isEqualTo(0.5);
                assertThat(epssFinding("CVE-2024-1000", List.of(), scores).epssScore())
                        .isNull();
            }
        }
    }

    @Test
    void epssMissingDataIsNotZeroAndUnrelatedRecordsCannotEnrichAFinding() {
        Map<String, EpssScore> scores = Map.of(
                "CVE-2024-1000", new EpssScore(0, 0),
                "CVE-2024-2000", new EpssScore(1, 1));

        assertThat(epssFinding("CVE-2024-1000", List.of(), scores).epssScore()).isZero();
        assertThat(epssFinding("CVE-2024-1000", List.of(), scores).epssPercentile())
                .isZero();
        assertThat(epssFinding("CVE-2024-2000", List.of(), scores).epssScore()).isEqualTo(1);
        assertThat(epssFinding("CVE-2024-3000", List.of(), scores).epssScore()).isNull();
        assertThat(epssFinding("GHSA-example", List.of(), scores).epssScore()).isNull();
        assertThat(epssFinding("CVE-2024-1000", List.of(), Map.of()).epssScore())
                .isNull();
    }

    private static DependencyVulnerabilityDto epssFinding(
            String id, List<String> aliases, Map<String, EpssScore> scores) {
        DependencyVulnerabilityDto vulnerability = new DependencyVulnerabilityDto(
                id, "summary", "details", "HIGH", 7.5, aliases, List.of(), List.of(), false, null, null, true);
        DependencyDto dependency = new DependencyDto(
                "org.example",
                "library",
                "1",
                "org.example:library",
                "test",
                0,
                "NONE",
                List.of(vulnerability),
                DependencyAssessmentDto.unknown());
        List<DependencyDto> result = DependencyReports.applyEpssScores(List.of(dependency), scores);
        assertThat(result.get(0).vulnerabilityCount()).isZero();
        assertThat(result.get(0).highestSeverity()).isEqualTo("NONE");
        assertThat(vulnerability.epssScore()).isNull();
        return result.get(0).vulnerabilities().get(0);
    }

    private static DependencyDto dependency(String groupId, String artifactId, String version) {
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "test",
                0,
                "NONE",
                List.of(),
                DependencyAssessmentDto.unknown());
    }

    private static DependencyDto vulnerableDependency(
            String groupId, String artifactId, String version, String severity) {
        String packageName = groupId + ":" + artifactId;
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(vulnerability("V-" + artifactId, severity));
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "test",
                vulnerabilities.size(),
                severity,
                vulnerabilities,
                DependencyAssessmentDto.unknown());
    }

    private static DependencyVulnerabilityDto vulnerability(String id, String severity) {
        return new DependencyVulnerabilityDto(id, null, null, severity, null, List.of(), List.of(), List.of());
    }

    @Test
    void ordersDependenciesByHighestSeverityThenPackageName() {
        DependenciesReport report = DependencyReports.report(
                true,
                "SCANNED",
                "done",
                1L,
                5,
                List.of(
                        dependency("org.zeta", "clean", "1.0.0"),
                        vulnerableDependency("org.zeta", "medium", "1.0.0", "MEDIUM"),
                        vulnerableDependency("org.zeta", "critical", "1.0.0", "CRITICAL"),
                        vulnerableDependency("org.alpha", "critical", "1.0.0", "CRITICAL"),
                        vulnerableDependency("org.alpha", "unknown", "1.0.0", "UNKNOWN")));

        assertThat(report.dependencies())
                .extracting(DependencyDto::packageName)
                .containsExactly(
                        "org.alpha:critical",
                        "org.zeta:critical",
                        "org.zeta:medium",
                        "org.alpha:unknown",
                        "org.zeta:clean");
    }

    @Test
    void vulnerabilityOrderSortsBySeverityRankThenId() {
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(
                vulnerability("V-unknown", "UNKNOWN"),
                vulnerability("V-low", "LOW"),
                vulnerability("V-medium", "MEDIUM"),
                vulnerability("V-high", "HIGH"),
                vulnerability("V-critical-b", "CRITICAL"),
                vulnerability("V-critical-a", "CRITICAL"));

        List<DependencyVulnerabilityDto> ordered = vulnerabilities.stream()
                .sorted(DependencyReports.VULNERABILITY_ORDER)
                .toList();

        assertThat(ordered)
                .extracting(DependencyVulnerabilityDto::id)
                .containsExactly("V-critical-a", "V-critical-b", "V-high", "V-medium", "V-low", "V-unknown");
    }

    @Test
    void vulnerabilityOrderSinksDismissedVulnerabilitiesBelowActiveOnesRegardlessOfSeverity() {
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(
                vulnerability("V-critical-dismissed", "CRITICAL").withDismissed(true),
                vulnerability("V-low", "LOW"),
                vulnerability("V-high", "HIGH"));

        List<DependencyVulnerabilityDto> ordered = vulnerabilities.stream()
                .sorted(DependencyReports.VULNERABILITY_ORDER)
                .toList();

        assertThat(ordered)
                .extracting(DependencyVulnerabilityDto::id)
                .containsExactly("V-high", "V-low", "V-critical-dismissed");
    }

    @Test
    void severityCountsExcludeDismissedVulnerabilities() {
        DependencyVulnerabilityDto active = vulnerability("V-active", "CRITICAL");
        DependencyVulnerabilityDto dismissed =
                vulnerability("V-dismissed", "HIGH").withDismissed(true);
        DependencyDto dependency = new DependencyDto(
                "org.example",
                "lib",
                "1.0.0",
                "org.example:lib",
                "test",
                1,
                "CRITICAL",
                List.of(active, dismissed),
                DependencyAssessmentDto.unknown());

        List<DependencySeverityCountDto> counts = DependencyReports.severityCounts(List.of(dependency));

        assertThat(counts)
                .filteredOn(count -> "CRITICAL".equals(count.severity()))
                .extracting(DependencySeverityCountDto::count)
                .containsExactly(1);
        assertThat(counts)
                .filteredOn(count -> "HIGH".equals(count.severity()))
                .extracting(DependencySeverityCountDto::count)
                .containsExactly(0);
    }

    @Test
    void highestSeverityIgnoresDismissedVulnerabilities() {
        List<DependencyVulnerabilityDto> vulnerabilities =
                List.of(vulnerability("V-critical", "CRITICAL").withDismissed(true), vulnerability("V-high", "HIGH"));

        assertThat(DependencyReports.highestSeverity(vulnerabilities)).isEqualTo("HIGH");
    }

    @Test
    void highestSeverityIsNoneWhenEveryVulnerabilityIsDismissed() {
        List<DependencyVulnerabilityDto> vulnerabilities =
                List.of(vulnerability("V-critical", "CRITICAL").withDismissed(true));

        assertThat(DependencyReports.highestSeverity(vulnerabilities)).isEqualTo("NONE");
    }

    @Test
    void normalizeSeverityStringMapsGitHubAdvisoryModerateToMedium() {
        // OSV.dev's database_specific.severity (GHSA-sourced) uses "MODERATE", never "MEDIUM".
        assertThat(DependencyReports.normalizeSeverity("MODERATE")).isEqualTo("MEDIUM");
        assertThat(DependencyReports.normalizeSeverity("moderate")).isEqualTo("MEDIUM");
    }

    @Test
    void normalizeSeverityStringPassesThroughKnownSeveritiesCaseInsensitively() {
        assertThat(DependencyReports.normalizeSeverity("critical")).isEqualTo("CRITICAL");
        assertThat(DependencyReports.normalizeSeverity(" High ")).isEqualTo("HIGH");
    }

    @Test
    void normalizeSeverityStringFallsBackToUnknown() {
        assertThat(DependencyReports.normalizeSeverity((String) null)).isEqualTo("UNKNOWN");
        assertThat(DependencyReports.normalizeSeverity("")).isEqualTo("UNKNOWN");
        assertThat(DependencyReports.normalizeSeverity("banana")).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizeSeverityScoreUsesTheOfficialCvssBands() {
        assertThat(DependencyReports.normalizeSeverity(0.0d)).isEqualTo("NONE");
        assertThat(DependencyReports.normalizeSeverity(0.1d)).isEqualTo("LOW");
        assertThat(DependencyReports.normalizeSeverity(4.0d)).isEqualTo("MEDIUM");
        assertThat(DependencyReports.normalizeSeverity(7.0d)).isEqualTo("HIGH");
        assertThat(DependencyReports.normalizeSeverity(9.0d)).isEqualTo("CRITICAL");
        assertThat(DependencyReports.normalizeSeverity(Double.NaN)).isEqualTo("UNKNOWN");
        assertThat(DependencyReports.normalizeSeverity(10.1d)).isEqualTo("UNKNOWN");
    }

    @Test
    void parseScoreComputesTheBaseScoreForARealCvssV31Vector() {
        // CVE-2021-44228 "Log4Shell" -- NVD-published Base Score 10.0.
        assertThat(DependencyReports.parseScore("CVSS_V3", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
                .isEqualTo(10.0d);
    }

    @Test
    void parseScoreSupportsTheCvss30Prefix() {
        assertThat(DependencyReports.parseScore("CVSS_V3", "CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isEqualTo(9.8d);
    }

    @Test
    void parseScoreReturnsNullForACvss40VectorRatherThanGuessing() {
        // No closed-form v4.0 Base Score equation exists (see CvssV3BaseScore's class Javadoc); callers
        // fall back to the database_specific.severity label for these advisories.
        assertThat(DependencyReports.parseScore(
                        "CVSS_V4", "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"))
                .isNull();
    }

    @Test
    void parseScoreDoesNotInterpretOtherSeverityTypesAsCvssV3() {
        assertThat(DependencyReports.parseScore("Ubuntu", "7.5")).isNull();
        assertThat(DependencyReports.parseScore("CVSS_V2", "7.5")).isNull();
        assertThat(DependencyReports.parseScore("CVSS_V3", "7.5")).isNull();
    }

    @Test
    void parseScoreReturnsNullForAnUnparseableOrMissingValue() {
        assertThat(DependencyReports.parseScore("CVSS_V3", "not-a-score")).isNull();
        assertThat(DependencyReports.parseScore("CVSS_V3", null)).isNull();
        assertThat(DependencyReports.parseScore(null, "CVSS:3.1/AV:N")).isNull();
    }

    @Test
    void dismissalKeyJoinsVulnerabilityIdAndPackageNameWithADoubleColonDelimiter() {
        assertThat(DependencyReports.dismissalKey("GHSA-xxxx-yyyy-zzzz", "com.example:widget"))
                .isEqualTo("GHSA-xxxx-yyyy-zzzz::com.example:widget");
    }

    @Test
    void applyDismissalsReturnsTheReportUnchangedWhenThereIsNothingToDismiss() {
        DependenciesReport report = DependencyReports.report(
                true, "SCANNED", "done", 1L, 1, List.of(vulnerableDependency("org.example", "lib", "1.0.0", "HIGH")));

        assertThat(DependencyReports.applyDismissals(report, Set.of())).isSameAs(report);
        assertThat(DependencyReports.applyDismissals(report, null)).isSameAs(report);
        assertThat(DependencyReports.applyDismissals(null, Set.of("x"))).isNull();
    }

    @Test
    void applyDismissalsMarksTheMatchingVulnerabilityAndRecomputesEveryDependentCount() {
        DependenciesReport report = DependencyReports.report(
                true,
                "SCANNED",
                "done",
                1L,
                1,
                List.of(vulnerableDependency("org.example", "lib", "1.0.0", "CRITICAL")));
        String key = DependencyReports.dismissalKey("V-lib", "org.example:lib");

        DependenciesReport updated = DependencyReports.applyDismissals(report, Set.of(key));

        DependencyDto dependency = updated.dependencies().get(0);
        assertThat(dependency.vulnerabilities())
                .extracting(DependencyVulnerabilityDto::dismissed)
                .containsExactly(true);
        assertThat(dependency.vulnerabilityCount()).isEqualTo(0);
        assertThat(dependency.highestSeverity()).isEqualTo("NONE");
        assertThat(updated.vulnerable()).isEqualTo(0);
        assertThat(updated.scan().vulnerabilitiesFound()).isEqualTo(0);
        assertThat(updated.severityCounts())
                .filteredOn(count -> "CRITICAL".equals(count.severity()))
                .extracting(DependencySeverityCountDto::count)
                .containsExactly(0);
    }

    @Test
    void applyDismissalsLeavesNonMatchingVulnerabilitiesActive() {
        DependenciesReport report = DependencyReports.report(
                true,
                "SCANNED",
                "done",
                1L,
                1,
                List.of(vulnerableDependency("org.example", "lib", "1.0.0", "CRITICAL")));

        DependenciesReport updated =
                DependencyReports.applyDismissals(report, Set.of("SOME-OTHER-ID::org.example:lib"));

        DependencyDto dependency = updated.dependencies().get(0);
        assertThat(dependency.vulnerabilities())
                .extracting(DependencyVulnerabilityDto::dismissed)
                .containsExactly(false);
        assertThat(dependency.vulnerabilityCount()).isEqualTo(1);
        assertThat(updated.vulnerable()).isEqualTo(1);
        assertThat(updated.scan().vulnerabilitiesFound()).isEqualTo(1);
    }

    @Test
    void applyDismissalsKeepsDismissedVulnerabilitiesInTheListSunkToTheBottom() {
        List<DependencyVulnerabilityDto> vulnerabilities =
                List.of(vulnerability("V-critical", "CRITICAL"), vulnerability("V-high", "HIGH"));
        DependencyDto dependencyDto = new DependencyDto(
                "org.example",
                "lib",
                "1.0.0",
                "org.example:lib",
                "test",
                2,
                "CRITICAL",
                vulnerabilities,
                DependencyAssessmentDto.unknown());
        DependenciesReport report = DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(dependencyDto));
        String key = DependencyReports.dismissalKey("V-critical", "org.example:lib");

        DependenciesReport updated = DependencyReports.applyDismissals(report, Set.of(key));

        DependencyDto dependency = updated.dependencies().get(0);
        assertThat(dependency.vulnerabilities())
                .extracting(DependencyVulnerabilityDto::id)
                .containsExactly("V-high", "V-critical");
        assertThat(dependency.vulnerabilityCount()).isEqualTo(1);
        assertThat(dependency.highestSeverity()).isEqualTo("HIGH");
    }

    @Test
    void applyDismissalsReordersDependenciesUsingTheirRecomputedSeverity() {
        DependencyDto critical = vulnerableDependency("org.example", "critical", "1.0.0", "CRITICAL");
        DependencyDto high = vulnerableDependency("org.example", "high", "1.0.0", "HIGH");
        DependenciesReport report = DependencyReports.report(true, "SCANNED", "done", 1L, 2, List.of(high, critical));

        DependenciesReport updated = DependencyReports.applyDismissals(
                report, Set.of(DependencyReports.dismissalKey("V-critical", "org.example:critical")));

        assertThat(updated.dependencies())
                .extracting(DependencyDto::packageName)
                .containsExactly("org.example:high", "org.example:critical");
    }

    @Test
    void scanCandidatesDeduplicatesPackageVersionsBeforeApplyingTheLimit() {
        DependencyDto first = dependency("org.example", "first", "1.0.0");
        DependencyDto duplicate = dependency("org.example", "first", "1.0.0");
        DependencyDto second = dependency("org.example", "second", "2.0.0");

        assertThat(DependencyReports.scanCandidates(List.of(first, duplicate, second), 2))
                .containsExactly(first, second);
        assertThat(DependencyReports.scanCandidateCount(List.of(first, duplicate, second)))
                .isEqualTo(2);
    }

    @Test
    void cveAliasesIncludesTheAdvisoryIdAndRejectsMalformedAliases() {
        DependencyVulnerabilityDto vulnerability = new DependencyVulnerabilityDto(
                "cve-2024-1234",
                null,
                null,
                "HIGH",
                null,
                List.of("CVE-2024-5678", "CVE-2024-5678", "CVE-2024-12&bad"),
                List.of(),
                List.of());
        DependencyDto dependency = new DependencyDto(
                "org.example",
                "lib",
                "1.0.0",
                "org.example:lib",
                "test",
                1,
                "HIGH",
                List.of(vulnerability),
                DependencyAssessmentDto.unknown());

        assertThat(DependencyReports.cveAliases(List.of(dependency))).containsExactly("CVE-2024-1234", "CVE-2024-5678");
    }

    @Test
    void epssCveChunksRespectFirstsCharacterLimitWithoutDroppingIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 1000; i < 1300; i++) {
            ids.add("CVE-2024-" + i);
        }

        List<List<String>> chunks = DependencyReports.epssCveChunks(ids);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(String.join(",", chunk))
                        .hasSizeLessThanOrEqualTo(DependencyReports.EPSS_CVE_PARAMETER_MAX_LENGTH));
        assertThat(chunks.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(ids);
    }

    @Test
    void applyEpssScoresMatchesACveAdvisoryWithoutRequiringAnAlias() {
        DependencyVulnerabilityDto vulnerability = new DependencyVulnerabilityDto(
                "CVE-2024-1234", null, null, "HIGH", null, List.of(), List.of(), List.of());
        DependencyDto dependency = new DependencyDto(
                "org.example",
                "lib",
                "1.0.0",
                "org.example:lib",
                "test",
                1,
                "HIGH",
                List.of(vulnerability),
                DependencyAssessmentDto.unknown());

        List<DependencyDto> enriched = DependencyReports.applyEpssScores(
                List.of(dependency), Map.of("CVE-2024-1234", new EpssScore(0.25d, 0.75d)));

        assertThat(enriched.get(0).vulnerabilities().get(0).epssScore()).isEqualTo(0.25d);
        assertThat(enriched.get(0).vulnerabilities().get(0).epssPercentile()).isEqualTo(0.75d);
    }

    @Test
    void skippedCandidateCountReportsWhatTheMaxPackagesBoundDroppedRatherThanHidingIt() {
        DependencyDto first = dependency("org.example", "first", "1.0.0");
        DependencyDto duplicate = dependency("org.example", "first", "1.0.0");
        DependencyDto second = dependency("org.example", "second", "2.0.0");
        DependencyDto third = dependency("org.example", "third", "3.0.0");
        List<DependencyDto> dependencies = List.of(first, duplicate, second, third);

        // Deduplication happens before the bound, so the duplicate is not counted as skipped.
        assertThat(DependencyReports.skippedCandidateCount(dependencies, 2)).isEqualTo(1);
        assertThat(DependencyReports.skippedCandidateCount(dependencies, 3)).isZero();
        assertThat(DependencyReports.skippedCandidateCount(dependencies, 500)).isZero();
        // scanCandidates clamps a non-positive bound to 1, and the skipped count must agree with it.
        assertThat(DependencyReports.skippedCandidateCount(dependencies, 0)).isEqualTo(2);
        assertThat(DependencyReports.scanCandidates(dependencies, 0)).hasSize(1);
    }

    @Test
    void reportDefaultsToUnavailableCoverageWhenTheAdapterCannotDescribeIt() {
        DependenciesReport report =
                DependencyReports.report(true, "SCANNED", "done", 1L, 1, List.of(dependency("org.example", "a", "1")));

        assertThat(report.coverage().status()).isEqualTo("UNAVAILABLE");
        assertThat(report.scan().packagesSkipped()).isZero();
    }

    @Test
    void applyDismissalsPreservesCoverageAndSkippedPackages() {
        DependencyCoverageDto coverage = DependencyCoverageDto.of(3, 1, List.of("mystery-1.0.0.jar"));
        DependenciesReport report = DependencyReports.report(
                true,
                "SCANNED",
                "done",
                1L,
                2,
                7,
                List.of(vulnerableDependency("org.example", "lib", "1.0.0", "CRITICAL")),
                coverage);

        DependenciesReport updated = DependencyReports.applyDismissals(
                report, Set.of(DependencyReports.dismissalKey("V-lib", "org.example:lib")));

        assertThat(updated.scan().packagesSkipped()).isEqualTo(7);
        assertThat(updated.coverage()).isEqualTo(coverage);
        assertThat(updated.coverage().status()).isEqualTo("INCOMPLETE");
        assertThat(updated.coverage().archivesIdentified()).isEqualTo(2);
    }

    @Test
    void orderFixedVersionsUsesMavenSemanticsAndStableDeduplication() {
        assertThat(DependencyReports.orderFixedVersions(List.of("1.0-sp1", "1.0", "1.0-rc1", "1.0", "2.0"), 4))
                .containsExactly("1.0-rc1", "1.0", "1.0-sp1", "2.0");
    }
}
