package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for dependency inventory and vulnerability findings.
 *
 * @param total the number of resolved dependency coordinates in the inventory
 * @param coverage how much of the application's real JAR set that inventory accounts for; never
 *     {@code null}, so a caller can always tell a complete inventory from a partial one
 */
public record DependenciesReport(
        boolean scanningEnabled,
        int total,
        int vulnerable,
        List<DependencySeverityCountDto> severityCounts,
        DependencyScanStatusDto scan,
        DependencyCoverageDto coverage,
        List<DependencyDto> dependencies) {

    public DependenciesReport {
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        dependencies = DtoCollections.immutableCopy(dependencies);
        coverage = coverage == null ? DependencyCoverageDto.unavailable() : coverage;
    }

    public String status() {
        return scan == null ? null : scan.status();
    }
}
