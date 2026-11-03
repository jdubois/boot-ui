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
        PageMetadata page,
        ThreadCaptureQuality quality,
        int hiddenBootUiThreads) {

    public ThreadDumpReport {
        deadlockedThreadIds = DtoCollections.immutableCopy(deadlockedThreadIds);
        stateCounts = DtoCollections.immutableCopy(stateCounts);
        threads = DtoCollections.immutableCopy(threads);
    }

    public ThreadDumpReport(
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
            PageMetadata page,
            ThreadCaptureQuality quality) {
        this(
                available,
                unavailableReason,
                capturedAt,
                totalThreads,
                daemonThreads,
                peakThreads,
                startedThreadCount,
                virtualThreadsSupported,
                cpuTimeSupported,
                deadlockDetected,
                deadlockedThreadIds,
                stateCounts,
                threads,
                page,
                quality,
                0);
    }

    public ThreadDumpReport(
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
        this(
                available,
                unavailableReason,
                capturedAt,
                totalThreads,
                daemonThreads,
                peakThreads,
                startedThreadCount,
                virtualThreadsSupported,
                cpuTimeSupported,
                deadlockDetected,
                deadlockedThreadIds,
                stateCounts,
                threads,
                page,
                null);
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
