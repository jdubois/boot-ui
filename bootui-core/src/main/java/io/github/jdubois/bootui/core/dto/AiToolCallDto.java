package io.github.jdubois.bootui.core.dto;

/**
 * Tool call (function call) emitted by Spring AI advisors.
 */
public record AiToolCallDto(String spanId, String name, long startEpochNanos, long durationNanos, String statusCode) {}
