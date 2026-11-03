package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadObservationReport(
        String id,
        String status,
        long startedAt,
        Long completedAt,
        int requestedSamples,
        int completedSamples,
        int clearedSamples,
        List<String> captureIds,
        String message,
        List<ThreadComparisonDto> threads,
        PageMetadata page,
        int hiddenBootUiThreads) {
    public ThreadObservationReport {
        captureIds = DtoCollections.immutableCopy(captureIds);
        threads = DtoCollections.immutableCopy(threads);
    }

    public ThreadObservationReport(
            String id,
            String status,
            long startedAt,
            Long completedAt,
            int requestedSamples,
            int completedSamples,
            int clearedSamples,
            List<String> captureIds,
            String message,
            List<ThreadComparisonDto> threads,
            PageMetadata page) {
        this(
                id,
                status,
                startedAt,
                completedAt,
                requestedSamples,
                completedSamples,
                clearedSamples,
                captureIds,
                message,
                threads,
                page,
                0);
    }
}
