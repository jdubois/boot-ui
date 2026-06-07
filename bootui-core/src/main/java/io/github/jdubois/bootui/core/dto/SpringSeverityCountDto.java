package io.github.jdubois.bootui.core.dto;

/**
 * Count of Spring Advisor rule violations by normalized severity.
 */
public record SpringSeverityCountDto(String severity, int count) {}
