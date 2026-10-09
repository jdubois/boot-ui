package io.github.jdubois.bootui.core.dto;

/**
 * Counted dashboard metric sorted by the backend before serialization.
 */
public record CopilotMetricCount(String label, int count) {}
