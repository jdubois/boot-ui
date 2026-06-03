package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Trace detail payload.
 */
public record TraceDetailDto(String traceId, List<SpanDto> spans) {}
