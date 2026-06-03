package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Kubernetes resource recommendation derived from the JVM memory
 * calculator and the current runtime snapshot.
 */
public record KubernetesMemoryRecommendationDto(
        long requestMemoryBytes,
        long limitMemoryBytes,
        long burstableRequestMemoryBytes,
        long currentSnapshotBytes,
        Long detectedContainerLimitBytes,
        String requestMemory,
        String limitMemory,
        String burstableRequestMemory,
        String currentSnapshotMemory,
        String detectedContainerLimitMemory,
        String qosClass,
        String confidence,
        List<String> warnings,
        String yaml,
        double maxRamPercentage,
        double initialRamPercentage,
        String javaToolOptions,
        boolean burstableEnabled,
        boolean actuatorProbesEnabled) {}
