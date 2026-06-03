package io.github.jdubois.bootui.core.dto;

/**
 * Count of architecture rule violations by normalized severity.
 */
public record ArchitectureSeverityCountDto(String severity, int count) {}
