package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Thread / Process Viewer panel.
 *
 * <p>Represents a single point-in-time snapshot of the JVM's live threads taken from
 * {@code ThreadMXBean}. When thread information cannot be read the report fails closed with
 * {@code available=false}, an {@code unavailableReason}, and stable empty collections.</p>
 */
public record ThreadDumpReport(
        boolean available,
        String unavailableReason,
        Long capturedAt,
        int totalThreads,
        int daemonThreads,
        int peakThreads,
        long startedThreadCount,
        boolean virtualThreadsSupported,
        boolean cpuTimeSupported,
        boolean deadlockDetected,
        List<Long> deadlockedThreadIds,
        List<ThreadStateCountDto> stateCounts,
        List<ThreadInfoDto> threads,
        PageMetadata page) {

    public ThreadDumpReport {
        deadlockedThreadIds = deadlockedThreadIds == null ? List.of() : List.copyOf(deadlockedThreadIds);
        stateCounts = stateCounts == null ? List.of() : List.copyOf(stateCounts);
        threads = threads == null ? List.of() : List.copyOf(threads);
    }

    /**
     * Stable empty report used when thread information is unavailable.
     */
    public static ThreadDumpReport unavailable(String reason) {
        return new ThreadDumpReport(
                false,
                reason,
                null,
                0,
                0,
                0,
                0L,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                new PageMetadata(0, 0, 0, 0, 0, false));
    }
}
