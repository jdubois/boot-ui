package io.github.jdubois.bootui.core.dto;

/**
 * Request to change the triage status of one exception group, e.g. {@code "ACKNOWLEDGED"} or
 * {@code "RESOLVED"}.
 */
public record ExceptionStatusUpdateRequest(String status) {}
