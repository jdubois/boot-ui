package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the local GraalVM native-image readiness panel. The findings list contains
 * triggered checks only, ordered by severity and impact.
 *
 * <p>{@code installable} and {@code installPath} describe whether the generated reachability metadata
 * can be written directly into the host application's source tree (only possible when the app runs
 * from an exploded build rather than a packaged jar). {@code installPath} is a display-friendly,
 * project-relative path, or the reason it is unavailable when {@code installable} is {@code false}.
 * {@code metadataDirectory} is the GraalVM-recommended, project-relative directory for the scaffold
 * with the resolved Maven coordinates substituted (or {@code <groupId>}/{@code <artifactId>}
 * placeholders when coordinates cannot be resolved); it is populated even when {@code installable} is
 * {@code false}, for example when running from a packaged jar. {@code dockerfile} carries a generated
 * native-image {@code Dockerfile-native} tailored to the host application.
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
        GraalVmMetadataSummaryDto metadata,
        boolean installable,
        String installPath,
        String metadataDirectory,
        GraalVmDockerfileDto dockerfile) {

    /** Returns a copy of this report with the install target resolution filled in. */
    public GraalVmReadinessReport withInstallTarget(boolean installable, String installPath, String metadataDirectory) {
        return new GraalVmReadinessReport(
                localOnly,
                disclaimer,
                basePackages,
                includeDependencies,
                classesAnalyzed,
                checksRun,
                findingsFound,
                severityCounts,
                scan,
                findings,
                dependenciesAnalyzed,
                dependenciesWithoutMetadata,
                dependencies,
                warnings,
                metadata,
                installable,
                installPath,
                metadataDirectory,
                dockerfile);
    }

    /** Returns a copy of this report with the generated native-image Dockerfile attached. */
    public GraalVmReadinessReport withDockerfile(GraalVmDockerfileDto dockerfile) {
        return new GraalVmReadinessReport(
                localOnly,
                disclaimer,
                basePackages,
                includeDependencies,
                classesAnalyzed,
                checksRun,
                findingsFound,
                severityCounts,
                scan,
                findings,
                dependenciesAnalyzed,
                dependenciesWithoutMetadata,
                dependencies,
                warnings,
                metadata,
                installable,
                installPath,
                metadataDirectory,
                dockerfile);
    }
}
