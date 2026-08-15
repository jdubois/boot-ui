package io.github.jdubois.bootui.core.dto;

/**
 * Request body for toggling Transactions panel recording.
 *
 * @param enabled desired recording state; {@code null} flips the current state
 */
public record TransactionRecordingRequest(Boolean enabled) {}
