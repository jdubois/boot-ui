package io.github.jdubois.bootui.core.dto;

/**
 * Count of Security Advisor findings by normalized severity.
 */
public record SecuritySeverityCountDto(String severity, int count) {}
