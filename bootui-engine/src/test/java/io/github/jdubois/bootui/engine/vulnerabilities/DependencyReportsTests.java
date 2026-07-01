package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencySeverityCountDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyReportsTests {

    private static DependencyDto dependency(String groupId, String artifactId, String version) {
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(groupId, artifactId, version, packageName, "test", 0, "NONE", List.of());
    }

    private static DependencyDto vulnerableDependency(
            String groupId, String artifactId, String version, String severity) {
        String packageName = groupId + ":" + artifactId;
        List<DependencyVulnerabilityDto> vulnerabilities = List.of(vulnerability("V-" + artifactId, severity));
        return new DependencyDto(
                groupId, artifactId, version, packageName, "test", vulnerabilities.size(), severity, vulnerabilities);
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
                "org.example", "lib", "1.0.0", "org.example:lib", "test", 1, "CRITICAL", List.of(active, dismissed));

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
    void parseScoreComputesTheBaseScoreForARealCvssV31Vector() {
        // CVE-2021-44228 "Log4Shell" -- NVD-published Base Score 10.0.
        assertThat(DependencyReports.parseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
                .isEqualTo(10.0d);
    }

    @Test
    void parseScoreSupportsTheCvss30Prefix() {
        assertThat(DependencyReports.parseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
                .isEqualTo(9.8d);
    }

    @Test
    void parseScoreReturnsNullForACvss40VectorRatherThanGuessing() {
        // No closed-form v4.0 Base Score equation exists (see CvssV3BaseScore's class Javadoc); callers
        // fall back to the database_specific.severity label for these advisories.
        assertThat(DependencyReports.parseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"))
                .isNull();
    }

    @Test
    void parseScoreFallsBackToABareNumericStringForNonCvssValues() {
        assertThat(DependencyReports.parseScore("7.5")).isEqualTo(7.5d);
    }

    @Test
    void parseScoreReturnsNullForAnUnparseableOrMissingValue() {
        assertThat(DependencyReports.parseScore("not-a-score")).isNull();
        assertThat(DependencyReports.parseScore(null)).isNull();
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
                "org.example", "lib", "1.0.0", "org.example:lib", "test", 2, "CRITICAL", vulnerabilities);
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
}
