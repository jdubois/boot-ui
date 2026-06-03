package io.github.jdubois.bootui.core.dto;

/**
 * Result of restarting a local development service.
 */
public record DevServiceRestartResult(String id, String status, String message) {}
