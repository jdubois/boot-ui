package io.github.jdubois.bootui.core.dto;

/**
 * Request body for {@code POST /bootui/api/sql-trace/recording} to start or stop recording.
 *
 * @param enabled desired recording state; {@code null} toggles the current state
 */
public record SqlTraceRecordingRequest(Boolean enabled) {}
