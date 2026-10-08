package io.github.jdubois.bootui.core.dto;

/**
 * Count of vulnerability advisories by normalized severity.
 */
public record DependencySeverityCountDto(String severity, int count) {}
