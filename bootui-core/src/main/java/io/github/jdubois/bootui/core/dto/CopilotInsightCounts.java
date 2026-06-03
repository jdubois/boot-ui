package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * Aggregate counters across a Copilot session's events.
 */
public record CopilotInsightCounts(
        int total, Map<String, Integer> byCategory, int errors, Long lastActivityEpochMillis) {}
