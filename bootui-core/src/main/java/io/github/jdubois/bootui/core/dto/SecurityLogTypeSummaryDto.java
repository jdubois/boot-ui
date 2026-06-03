package io.github.jdubois.bootui.core.dto;

/**
 * Count of retained audit events by type.
 */
public record SecurityLogTypeSummaryDto(String type, int count) {}
