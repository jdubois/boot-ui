package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadCaptureList(
        boolean available,
        boolean lowFootprint,
        long retainedBytes,
        int captureLimit,
        long retainedByteLimit,
        int observationSamples,
        int maxObservationSamples,
        long observationIntervalMillis,
        long captureDeadlineMillis,
        String observationId,
        List<ThreadCaptureSummary> captures,
        PageMetadata page) {
    public ThreadCaptureList {
        captures = DtoCollections.immutableCopy(captures);
    }
}
