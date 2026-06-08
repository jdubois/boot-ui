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
        String recommendation,
        boolean dismissed) {

    public ArchitectureRuleResultDto(
            String id,
            String name,
            String category,
            String severity,
            String description,
            String status,
            int violationCount,
            List<String> sampleViolations,
            String recommendation) {
        this(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                false);
    }

    public ArchitectureRuleResultDto withDismissed(boolean dismissed) {
        return new ArchitectureRuleResultDto(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                dismissed);
    }
}
