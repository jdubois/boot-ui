package io.github.jdubois.bootui.core.dto;

/**
 * Result of a Live Activity "Use the existing datasource" runtime-switch action.
 *
 * @param status a short machine-readable outcome: {@code "already-active"}, {@code "unavailable"},
 *     {@code "blocked"}, {@code "failed"} or {@code "success"}
 * @param message human-readable detail, including (on success) the honest caveat that the switch is
 *     runtime-only and will not survive a restart
 * @param tableName the table name that was (or would be) verified/created
 */
public record ActivitySwitchResult(String status, String message, String tableName) {}
