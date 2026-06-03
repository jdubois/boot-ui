package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Response payload for the sessions list endpoint.
 */
public record CopilotSessionListDto(
        boolean available,
        String unavailableReason,
        String sessionStateDir,
        int total,
        int returned,
        int maxSessions,
        List<CopilotSessionSummary> sessions,
        List<String> warnings) {}
