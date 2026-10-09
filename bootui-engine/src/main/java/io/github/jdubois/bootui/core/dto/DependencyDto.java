package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Maven dependency discovered on the running application's classpath.
 *
 * <p>{@code runtimeReach} is the BootUI agent's evidence of whether its code loaded ({@code docs/PLAN-v2.md} §5.15),
 * {@code null} without it; it never changes {@code highestSeverity} or any count.
 */
public record DependencyDto(
        String groupId,
        String artifactId,
        String version,
        String packageName,
        String source,
        int vulnerabilityCount,
        String highestSeverity,
        List<DependencyVulnerabilityDto> vulnerabilities,
        DependencyAssessmentDto assessment,
        RuntimeReachDto runtimeReach) {

    public DependencyDto {
        vulnerabilities = DtoCollections.immutableCopy(vulnerabilities);
        assessment = assessment == null ? DependencyAssessmentDto.unknown() : assessment;
    }

    public DependencyDto(
            String groupId,
            String artifactId,
            String version,
            String packageName,
            String source,
            int vulnerabilityCount,
            String highestSeverity,
            List<DependencyVulnerabilityDto> vulnerabilities,
            DependencyAssessmentDto assessment) {
        this(
                groupId,
                artifactId,
                version,
                packageName,
                source,
                vulnerabilityCount,
                highestSeverity,
                vulnerabilities,
                assessment,
                null);
    }

    /** This dependency with its runtime reach and its advisories', every other field unchanged. */
    public DependencyDto withRuntimeReach(RuntimeReachDto reach, List<DependencyVulnerabilityDto> vulnerabilities) {
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                source,
                vulnerabilityCount,
                highestSeverity,
                vulnerabilities,
                assessment,
                reach);
    }
}
