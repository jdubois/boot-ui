package io.github.jdubois.bootui.core.dto;

/**
 * Count of Memory Advisor findings by normalized severity.
 */
public record MemorySeverityCountDto(String severity, int count) {}
