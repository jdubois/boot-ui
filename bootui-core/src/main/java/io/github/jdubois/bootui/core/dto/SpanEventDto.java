package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Discrete event recorded inside a span.
 */
public record SpanEventDto(String name, long timeOffsetNanos, List<SpanAttributeDto> attributes) {}
