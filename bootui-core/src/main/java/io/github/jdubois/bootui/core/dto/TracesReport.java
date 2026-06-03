package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Traces list payload.
 */
public record TracesReport(boolean enabled, int retained, int capacity, List<TraceSummaryDto> traces) {}
