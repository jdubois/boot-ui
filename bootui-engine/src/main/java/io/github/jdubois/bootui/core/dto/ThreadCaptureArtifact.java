package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Versioned BootUI format; it does not claim native JVM dump compatibility. */
public record ThreadCaptureArtifact(
        String format, int schemaVersion, ThreadCaptureSummary capture, List<CapturedThreadDto> threads) {
    public ThreadCaptureArtifact {
        threads = DtoCollections.immutableCopy(threads);
    }
}
