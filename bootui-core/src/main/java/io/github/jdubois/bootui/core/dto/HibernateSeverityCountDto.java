package io.github.jdubois.bootui.core.dto;

/**
 * Count of Hibernate Advisor rule violations by normalized severity.
 */
public record HibernateSeverityCountDto(String severity, int count) {}
