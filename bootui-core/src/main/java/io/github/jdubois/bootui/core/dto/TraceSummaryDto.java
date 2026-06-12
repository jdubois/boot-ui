package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Summary used by the Traces list view.
 */
public record TraceSummaryDto(
        String traceId,
        String rootSpanName,
        String httpPath,
        List<String> services,
        long startEpochNanos,
        long endEpochNanos,
        long durationNanos,
        int spanCount,
        boolean hasError,
        boolean hasAi) {}
