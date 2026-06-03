package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detail of a single AI chat span, including linked tool calls and vector operations from the same trace.
 */
public record AiChatDetailDto(
        AiChatSummaryDto summary,
        List<AiToolCallDto> toolCalls,
        List<AiVectorOpDto> vectorOperations,
        List<SpanAttributeDto> attributes,
        List<SpanEventDto> events,
        boolean contentCaptured,
        String contentBanner) {}
