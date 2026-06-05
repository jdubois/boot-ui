package io.github.jdubois.bootui.core.dto;

/**
 * Sanitized summary of a single Copilot CLI session.
 */
public record CopilotSessionSummary(
        String id,
        String filename,
        Long startedAtEpochMillis,
        Long updatedAtEpochMillis,
        String model,
        String workingDirectory,
        String status,
        int eventCount,
        int turnCount,
        Long inputTokens,
        Long outputTokens,
        int errorCount,
        String lastActivitySummary,
        boolean schemaDrift) {}
