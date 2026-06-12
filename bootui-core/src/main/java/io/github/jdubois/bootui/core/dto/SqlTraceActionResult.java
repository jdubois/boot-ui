package io.github.jdubois.bootui.core.dto;

/**
 * Result of a SQL Trace state-changing action (clear buffer or toggle recording).
 *
 * @param recording recording state after the action
 * @param cleared number of buffered executions removed (0 for non-clear actions)
 * @param message human-readable summary of what changed
 */
public record SqlTraceActionResult(boolean recording, int cleared, String message) {}
