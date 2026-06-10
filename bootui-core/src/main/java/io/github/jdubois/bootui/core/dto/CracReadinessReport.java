package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local CRaC (Coordinated Restore at Checkpoint) panel. It combines the
 * live runtime status (is CRaC available right now?) with a heuristic readiness advisor whose
 * findings list contains triggered checks only, ordered by severity and impact.
 *
 * <p>{@code generatedFiles} carries the container assets BootUI generates for the host application —
 * the tailored {@code Dockerfile-crac} and its {@code checkpoint-and-run.sh} entrypoint — each with
 * its own content and whether it can be written directly into the project directory.
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
        List<String> warnings,
        List<CracGeneratedFileDto> generatedFiles) {

    /** Returns a copy of this report with the generated container assets attached. */
    public CracReadinessReport withGeneratedFiles(List<CracGeneratedFileDto> generatedFiles) {
        return new CracReadinessReport(
                localOnly,
                disclaimer,
                runtime,
                basePackages,
                classesAnalyzed,
                checksRun,
                findingsFound,
                severityCounts,
                scan,
                findings,
                warnings,
                generatedFiles);
    }
}
