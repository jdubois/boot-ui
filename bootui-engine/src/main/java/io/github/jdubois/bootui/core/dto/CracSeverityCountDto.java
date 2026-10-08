package io.github.jdubois.bootui.core.dto;

/**
 * Count of CRaC readiness findings by normalized severity.
 */
public record CracSeverityCountDto(String severity, int count) {}
