package io.github.jdubois.bootui.core.dto;

/**
 * Count of REST API Advisor findings by normalized severity.
 */
public record RestApiSeverityCountDto(String severity, int count) {}
