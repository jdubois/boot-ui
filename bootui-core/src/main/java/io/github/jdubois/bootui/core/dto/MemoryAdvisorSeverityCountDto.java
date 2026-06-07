package io.github.jdubois.bootui.core.dto;

/**
 * Count of Memory Advisor rule violations by normalized severity.
 */
public record MemoryAdvisorSeverityCountDto(String severity, int count) {}
