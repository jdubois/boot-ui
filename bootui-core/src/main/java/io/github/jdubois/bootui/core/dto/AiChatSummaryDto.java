package io.github.jdubois.bootui.core.dto;

/**
 * Summary of a single AI chat completion span.
 */
public record AiChatSummaryDto(
        String traceId,
        String spanId,
        long startEpochNanos,
        long durationNanos,
        String provider,
        String requestModel,
        String responseModel,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        String finishReason,
        String statusCode,
        String operation,
        int toolCallCount,
        int vectorOperationCount) {}
