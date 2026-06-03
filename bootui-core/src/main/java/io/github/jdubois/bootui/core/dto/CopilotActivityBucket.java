package io.github.jdubois.bootui.core.dto;

/**
 * Time bucket for the Copilot dashboard activity chart.
 */
public record CopilotActivityBucket(Long startEpochMillis, Long endEpochMillis, int eventCount, int errorCount) {}
