package io.github.jdubois.bootui.core.dto;

/**
 * Count of REST API Advisor rule violations by normalized severity.
 */
public record RestApiAdvisorSeverityCountDto(String severity, int count) {}
