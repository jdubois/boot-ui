package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for dependency inventory and vulnerability findings.
 */
public record DependenciesReport(
        boolean scanningEnabled,
        int total,
        int vulnerable,
        List<DependencySeverityCountDto> severityCounts,
        DependencyScanStatusDto scan,
        List<DependencyDto> dependencies) {

    public String status() {
        return scan == null ? null : scan.status();
    }
}
