package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.List;
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
}
