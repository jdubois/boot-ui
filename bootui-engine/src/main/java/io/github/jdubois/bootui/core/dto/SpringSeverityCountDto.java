package io.github.jdubois.bootui.core.dto;

/**
 * Count of Spring or Quarkus Application Advisor findings by normalized severity.
 */
public record SpringSeverityCountDto(String severity, int count) {}
