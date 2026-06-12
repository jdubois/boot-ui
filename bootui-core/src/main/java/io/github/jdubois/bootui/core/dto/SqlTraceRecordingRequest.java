package io.github.jdubois.bootui.core.dto;

/**
 * Request body for toggling SQL trace recording.
 *
 * @param enabled desired recording state; {@code null} flips the current state
 */
public record SqlTraceRecordingRequest(Boolean enabled) {}
