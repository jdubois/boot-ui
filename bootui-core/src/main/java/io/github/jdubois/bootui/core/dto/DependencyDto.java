package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Maven dependency discovered on the running application's classpath.
 */
public record DependencyDto(
        String groupId,
        String artifactId,
        String version,
        String packageName,
        String source,
        int vulnerabilityCount,
        String highestSeverity,
        List<DependencyVulnerabilityDto> vulnerabilities) {}
