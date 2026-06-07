package io.github.jdubois.bootui.core.dto;

/**
 * Count of Spring Advisor rule violations by normalized severity.
 */
public record SpringAdvisorSeverityCountDto(String severity, int count) {}
