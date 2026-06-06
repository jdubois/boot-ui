package io.github.jdubois.bootui.core.dto;

/**
 * Headline runtime metrics surfaced alongside the Memory Advisor findings so the panel can
 * render a snapshot summary without re-querying the Memory, Threads, and Heap Dump panels.
 */
public record MemoryAdvisorSummaryDto(
        long heapUsedBytes,
        long heapMaxBytes,
        int heapUsedPercent,
        int liveThreads,
        int peakThreads,
        boolean deadlockDetected,
        int loadedClasses,
        boolean histogramAvailable) {}
