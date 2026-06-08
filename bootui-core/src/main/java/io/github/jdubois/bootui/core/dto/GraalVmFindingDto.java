package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One heuristic GraalVM native-image readiness finding evaluated against the host application
 * classes.
 */
public record GraalVmFindingDto(
        String id,
        String name,
        String category,
        String severity,
        String description,
        String status,
        int occurrenceCount,
        List<String> sampleOccurrences,
        String recommendation,
        String learnMoreUrl) {}
