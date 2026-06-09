package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local CRaC (Coordinated Restore at Checkpoint) panel. It combines the
 * live runtime status (is CRaC available right now?) with a heuristic readiness advisor whose
 * findings list contains triggered checks only, ordered by severity and impact.
 */
public record CracReadinessReport(
        boolean localOnly,
        String disclaimer,
        CracRuntimeStatusDto runtime,
        List<String> basePackages,
        int classesAnalyzed,
        int checksRun,
        int findingsFound,
        List<CracSeverityCountDto> severityCounts,
        CracScanStatusDto scan,
        List<CracFindingDto> findings,
        List<String> warnings) {}
