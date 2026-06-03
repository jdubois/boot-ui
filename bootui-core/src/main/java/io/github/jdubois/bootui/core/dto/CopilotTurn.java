package io.github.jdubois.bootui.core.dto;

/**
 * One turn of activity in a Copilot session.
 */
public record CopilotTurn(int index, Long startedAtEpochMillis, Long durationMillis, String summary, int eventCount) {}
