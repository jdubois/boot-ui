package io.github.jdubois.bootui.core.dto;

/**
 * The body of {@code POST /live-memory/footprint}: whether low-footprint mode should be on.
 *
 * @param enabled {@code true} to enter low-footprint mode, {@code false} to leave it
 */
public record LowFootprintRequest(boolean enabled) {}
