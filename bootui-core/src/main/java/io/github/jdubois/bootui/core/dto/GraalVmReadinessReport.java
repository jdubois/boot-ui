package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local GraalVM native-image readiness panel. The findings list contains
 * triggered checks only, ordered by severity and impact.
 */
public record GraalVmReadinessReport(
        boolean localOnly,
        String disclaimer,
        List<String> basePackages,
        boolean includeDependencies,
        int classesAnalyzed,
        int checksRun,
        int findingsFound,
        List<GraalVmSeverityCountDto> severityCounts,
        GraalVmScanStatusDto scan,
        List<GraalVmFindingDto> findings,
        int dependenciesAnalyzed,
        int dependenciesWithoutMetadata,
        List<GraalVmDependencyDto> dependencies,
        List<String> warnings,
        GraalVmMetadataSummaryDto metadata) {}
