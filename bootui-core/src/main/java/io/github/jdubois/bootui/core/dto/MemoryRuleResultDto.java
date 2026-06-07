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
        String learnMoreUrl) {}
