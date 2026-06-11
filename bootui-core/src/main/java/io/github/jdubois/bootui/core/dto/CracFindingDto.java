package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One heuristic CRaC checkpoint/restore readiness finding evaluated against the host application
 * classes.
 */
public record CracFindingDto(
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
