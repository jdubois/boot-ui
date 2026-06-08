package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Outcome of one Memory Advisor rule evaluated against the JVM runtime snapshot
 * (heap, memory pools, GC configuration, threads, heap content, and class loading).
 */
public record MemoryRuleResultDto(
        String id,
        String name,
        String category,
        String severity,
        String description,
        String status,
        int violationCount,
        List<String> sampleViolations,
        String recommendation,
        String learnMoreUrl,
        boolean dismissed) {

    public MemoryRuleResultDto(
            String id,
            String name,
            String category,
            String severity,
            String description,
            String status,
            int violationCount,
            List<String> sampleViolations,
            String recommendation,
            String learnMoreUrl) {
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
                learnMoreUrl,
                false);
    }

    public MemoryRuleResultDto withDismissed(boolean dismissed) {
        return new MemoryRuleResultDto(
                id,
                name,
                category,
                severity,
                description,
                status,
                violationCount,
                sampleViolations,
                recommendation,
                learnMoreUrl,
                dismissed);
    }
}
