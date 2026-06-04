package io.github.jdubois.bootui.core.dto;

/**
 * Count of Spring Security Advisor rule violations by normalized severity.
 */
public record SecurityAdvisorSeverityCountDto(String severity, int count) {}
