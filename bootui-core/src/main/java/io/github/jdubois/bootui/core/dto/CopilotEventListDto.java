package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Paginated/filtered events for a single session.
 */
public record CopilotEventListDto(String sessionId, int total, int returned, List<CopilotActivityEvent> events) {}
