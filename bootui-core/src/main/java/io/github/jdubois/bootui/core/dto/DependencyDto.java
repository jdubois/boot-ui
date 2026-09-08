package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Maven dependency discovered on the running application's classpath.
 *
 * @param assessmentComplete whether its OSV query pagination and every required advisory detail
 *     and package association were resolved; this does not imply known severity or complete inventory
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
        boolean assessmentComplete) {

    public DependencyDto(
            String groupId,
            String artifactId,
            String version,
            String packageName,
            String source,
            int vulnerabilityCount,
            String highestSeverity,
            List<DependencyVulnerabilityDto> vulnerabilities) {
        this(
                groupId,
                artifactId,
                version,
                packageName,
                source,
                vulnerabilityCount,
                highestSeverity,
                vulnerabilities,
                false);
    }

    public DependencyDto {
        vulnerabilities = DtoCollections.immutableCopy(vulnerabilities);
    }
}
