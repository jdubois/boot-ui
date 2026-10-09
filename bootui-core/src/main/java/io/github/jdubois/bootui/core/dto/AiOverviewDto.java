package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * AI Framework overview payload.
 *
 * @param springAiDetected whether Spring AI is on the class path; always {@code false} on Quarkus
 * @param langChain4jDetected whether LangChain4j, including Quarkus LangChain4j, is on the class path
 * @param aiFrameworkDetected whether any supported AI framework is on the class path, on every stack
 */
public record AiOverviewDto(
        boolean enabled,
        boolean springAiDetected,
        boolean langChain4jDetected,
        boolean aiFrameworkDetected,
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
        String contentBanner) {

    public AiOverviewDto {
        tokensByModel = DtoCollections.immutableCopy(tokensByModel);
        callsByModel = DtoCollections.immutableCopy(callsByModel);
        recent = DtoCollections.immutableCopy(recent);
    }
}
