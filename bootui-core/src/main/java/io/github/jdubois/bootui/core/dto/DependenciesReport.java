package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for dependency inventory and vulnerability findings.
 *
 * @param total the number of resolved dependency coordinates in the inventory
 * @param coverage how much of the application's real JAR set that inventory accounts for; never
 *     {@code null}, so a caller can always tell a complete inventory from a partial one
 * @param runtimeReach whether rows carry the BootUI agent's runtime reach, and why not; {@code null} when the
 *     adapter has no Code Inventory to read it from. Reach never changes any count, severity, or evidence.
 */
public record DependenciesReport(
        boolean scanningEnabled,
        int total,
        int vulnerable,
        List<DependencySeverityCountDto> severityCounts,
        DependencyScanStatusDto scan,
        DependencyCoverageDto coverage,
        List<DependencyDto> dependencies,
        AdvisorEvidenceDto evidence,
        RuntimeReachSummaryDto runtimeReach) {

    public DependenciesReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        dependencies = DtoCollections.immutableCopy(dependencies);
        coverage = coverage == null ? DependencyCoverageDto.unavailable() : coverage;
    }

    public DependenciesReport(
            boolean scanningEnabled,
            int total,
            int vulnerable,
            List<DependencySeverityCountDto> severityCounts,
            DependencyScanStatusDto scan,
            DependencyCoverageDto coverage,
            List<DependencyDto> dependencies,
            AdvisorEvidenceDto evidence) {
        this(scanningEnabled, total, vulnerable, severityCounts, scan, coverage, dependencies, evidence, null);
    }

    public String status() {
        return scan == null ? null : scan.status();
    }

    /** This report with runtime reach, every other field unchanged. */
    public DependenciesReport withRuntimeReach(RuntimeReachSummaryDto reach, List<DependencyDto> dependencies) {
        return new DependenciesReport(
                scanningEnabled, total, vulnerable, severityCounts, scan, coverage, dependencies, evidence, reach);
    }
}
