package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detailed span record returned by the Traces detail endpoint.
 */
public record SpanDto(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        String serviceName,
        String scope,
        long startEpochNanos,
        long endEpochNanos,
        long durationNanos,
        String statusCode,
        String statusMessage,
        List<SpanAttributeDto> attributes,
        List<SpanEventDto> events) {}
