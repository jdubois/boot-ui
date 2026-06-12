package io.github.jdubois.bootui.core.dto;

/**
 * A single event on a correlated request's unified timeline in the Diagnostics dashboard.
 *
 * <p>Entries from different signal sources (HTTP exchanges, SQL executions, exceptions, security
 * events, log lines, spans) are normalized into this shared shape so the UI can render them as one
 * chronological story.
 *
 * @param kind signal source: {@code HTTP}, {@code SQL}, {@code EXCEPTION}, {@code SECURITY},
 *     {@code LOG} or {@code SPAN}
 * @param timestamp event time in epoch milliseconds
 * @param title short, already-masked headline (e.g. {@code POST /orders}, the SQL statement type,
 *     or the exception class)
 * @param detail longer, already-masked description (e.g. the SQL text, exception message, or
 *     security event type); may be {@code null}
 * @param durationMs duration in milliseconds when the event represents something timed; {@code null}
 *     otherwise
 * @param severity {@code ERROR}, {@code WARN} or {@code INFO}
 * @param thread the capturing thread name when known; {@code null} otherwise
 * @param slow whether the event was flagged slow by its source panel
 */
public record DiagnosticsTimelineEntryDto(
        String kind,
        long timestamp,
        String title,
        String detail,
        Long durationMs,
        String severity,
        String thread,
        boolean slow) {}
