package io.github.jdubois.bootui.core.dto;

/**
 * Tail of logs for one local development service.
 */
public record DevServiceLogReport(String id, String logs, boolean truncated, int maxBytes) {}
