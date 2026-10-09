package io.github.jdubois.bootui.core.dto;

/**
 * Count of Database Advisor findings by normalized severity.
 */
public record DatabaseAdvisorSeverityCountDto(String severity, int count) {}
