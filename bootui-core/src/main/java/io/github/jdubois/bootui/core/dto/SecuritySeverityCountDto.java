package io.github.jdubois.bootui.core.dto;

/**
 * Count of Spring Security Advisor rule violations by normalized severity.
 */
public record SecuritySeverityCountDto(String severity, int count) {}
