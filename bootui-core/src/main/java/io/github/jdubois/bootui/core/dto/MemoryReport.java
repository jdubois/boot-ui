package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Snapshot of JVM memory metrics.
 */
public record MemoryReport(
        MemoryPoolDto heap,
        MemoryPoolDto nonHeap,
        List<MemoryPoolDto> pools,
        List<String> jvmInputArguments,
        String suggestedJvmOptions,
        MemoryCalculationDto calculation,
        KubernetesMemoryRecommendationDto kubernetes) {}
