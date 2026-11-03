package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadCaptureSummary(
        String id,
        ApplicationRunDto run,
        long capturedAt,
        long completedAt,
        long expiresAt,
        long durationMillis,
        long retainedBytes,
        ThreadCaptureQuality quality,
        List<ThreadStateCountDto> stateCounts,
        boolean deadlockDetected,
        List<Long> deadlockedThreadIds) {
    public ThreadCaptureSummary {
        stateCounts = DtoCollections.immutableCopy(stateCounts);
        deadlockedThreadIds = DtoCollections.immutableCopy(deadlockedThreadIds);
    }
}
