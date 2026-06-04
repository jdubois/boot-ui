package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * AI Usage overview payload.
 */
public record AiOverviewDto(
        boolean enabled,
        boolean springAiDetected,
        boolean langChain4jDetected,
        int totalChats,
        long totalInputTokens,
        long totalOutputTokens,
        Map<String, Long> tokensByModel,
        Map<String, Integer> callsByModel,
        int errorCount,
        long averageDurationNanos,
        int toolCallCount,
        int vectorOperationCount,
        int embeddingCount,
        List<AiChatSummaryDto> recent,
        String contentBanner) {}
