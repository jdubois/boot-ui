package io.github.jdubois.bootui.core.dto;

/**
 * Result of a DevTools reload or restart action.
 */
public record DevToolsActionResult(String action, String status, String message) {}
