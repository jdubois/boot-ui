package io.github.jdubois.bootui.core.dto;

/**
 * A single sanitized activity event observed in a Copilot session.
 *
 * <p>Only allowlisted fields are returned. Raw arguments, command output, file diffs,
 * and prompts are deliberately excluded from this DTO. The opt-in raw endpoint
 * exposes the source JSON locally on demand.</p>
 */
public record CopilotActivityEvent(
        String id,
        int turnIndex,
        Long timestampEpochMillis,
        String type,
        String toolName,
        String category,
        String summary,
        Boolean success) {}
