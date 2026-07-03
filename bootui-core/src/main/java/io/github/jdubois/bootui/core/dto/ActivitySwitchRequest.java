package io.github.jdubois.bootui.core.dto;

/**
 * Request body for the Live Activity "Use the existing datasource" runtime-switch action — mirrors
 * {@link FlywayActionRequest}'s confirmation-gating shape.
 */
public record ActivitySwitchRequest(Boolean confirm) {}
