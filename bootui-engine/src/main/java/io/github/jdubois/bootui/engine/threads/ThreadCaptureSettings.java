package io.github.jdubois.bootui.engine.threads;

/** Static limits shared by every transport and by live and retained acquisition. */
public record ThreadCaptureSettings(
        int maxThreads,
        int maxStackDepth,
        int maxOwnedLocks,
        long captureDeadlineMillis,
        boolean includeSynchronizers,
        int maxCaptures,
        long maxRetainedBytes,
        long maxCaptureBytes,
        long ttlMillis,
        int observationSamples,
        long observationIntervalMillis) {

    public static final long SESSION_BYTES = 524_288;

    public ThreadCaptureSettings {
        if (maxThreads < 1
                || maxThreads > 10_000
                || maxStackDepth < 1
                || maxStackDepth > 256
                || maxOwnedLocks < 1
                || maxOwnedLocks > 1024
                || captureDeadlineMillis < 1
                || captureDeadlineMillis > 30_000
                || maxCaptures < 1
                || maxCaptures > 20
                || maxCaptureBytes < 4096
                || maxCaptureBytes > 16 * 1024 * 1024L
                || maxRetainedBytes < maxCaptureBytes + SESSION_BYTES
                || maxRetainedBytes > 64 * 1024 * 1024L
                || ttlMillis < 30_000
                || ttlMillis > 600_000
                || observationSamples < 2
                || observationSamples > 5
                || observationSamples > maxCaptures
                || observationIntervalMillis < 1000
                || observationIntervalMillis > 30_000
                || observationIntervalMillis * (observationSamples - 1) + captureDeadlineMillis > 30_000
                || observationSamples * maxCaptureBytes + SESSION_BYTES > maxRetainedBytes) {
            throw new IllegalArgumentException("Invalid BootUI thread capture limits or observation reservation.");
        }
    }

    public static ThreadCaptureSettings defaults() {
        return new ThreadCaptureSettings(
                2048, 128, 256, 2000, false, 6, 16 * 1024 * 1024L, 4 * 1024 * 1024L, 300_000, 3, 5000);
    }

    public int maxObservationSamples() {
        return (int) Math.min(
                Math.min(5, maxCaptures),
                Math.min(
                        (maxRetainedBytes - SESSION_BYTES) / maxCaptureBytes,
                        1 + (30_000 - captureDeadlineMillis) / 1000));
    }
}
