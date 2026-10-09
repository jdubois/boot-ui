package io.github.jdubois.bootui.core.dto;

/**
 * Count of GraalVM readiness findings by normalized severity.
 */
public record GraalVmSeverityCountDto(String severity, int count) {}
