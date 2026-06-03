package io.github.jdubois.bootui.core.dto;

/**
 * Vector store operation linked to the same trace.
 */
public record AiVectorOpDto(
        String spanId,
        String operation,
        String collectionName,
        long startEpochNanos,
        long durationNanos,
        String statusCode) {}
