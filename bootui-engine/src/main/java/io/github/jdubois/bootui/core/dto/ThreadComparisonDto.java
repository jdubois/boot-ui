package io.github.jdubois.bootui.core.dto;

public record ThreadComparisonDto(
        String observationId,
        long threadId,
        String name,
        int observedSamples,
        int matchingStackOccurrences,
        int consecutiveStackStreak,
        int stateChanges,
        long firstObservedAt,
        long lastObservedAt,
        Long cpuDeltaNanos,
        Long elapsedNanos,
        Double singleCoreCpuPercent,
        String cpuUnavailableReason,
        String interpretation) {}
