package io.github.jdubois.bootui.core.dto;

/**
 * One sanitized progress event emitted while a "Fix it with Copilot" run executes.
 *
 * @param sequence monotonically increasing index within the run
 * @param timestamp epoch milliseconds when the event was recorded
 * @param type event category: {@code status}, {@code log}, {@code tool}, {@code diff},
 *     {@code error} or {@code done}
 * @param message human-readable, sanitized message
 */
public record CopilotFixEventDto(long sequence, long timestamp, String type, String message) {}
