package io.github.jdubois.bootui.core.dto;

/**
 * Tool call emitted by AI framework telemetry.
 */
public record AiToolCallDto(String spanId, String name, long startEpochNanos, long durationNanos, String statusCode) {}
