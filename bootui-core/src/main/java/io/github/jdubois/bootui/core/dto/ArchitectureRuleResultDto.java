package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one architecture rule violation evaluated against the host application classes.
 */
public record ArchitectureRuleResultDto(
        String id,
        String name,
        String category,
        String severity,
        String description,
        String status,
        int violationCount,
        List<String> sampleViolations,
        String recommendation) {}
