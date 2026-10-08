package io.github.jdubois.bootui.core.dto;

/**
 * One bounded audit-event data entry.
 */
public record SecurityLogDataDto(String name, String value, boolean masked, boolean truncated) {}
