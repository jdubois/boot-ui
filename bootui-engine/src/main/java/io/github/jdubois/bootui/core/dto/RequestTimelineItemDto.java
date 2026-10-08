package io.github.jdubois.bootui.core.dto;

/**
 * One recorded piece of a request's work on its timeline ({@code docs/PLAN-v2.md} §5.3). Offsets come from epoch
 * milliseconds, so they are exact to the millisecond; durations come from the JVM's monotonic clock.
 *
 * @param source the journal source, such as {@code sql} or {@code transaction}
 * @param label what it was, such as a statement or a method
 * @param detail a secondary line, such as a data source, or {@code null}
 * @param offsetMillis when it started, from the request's start
 * @param durationMicros how long it took, or {@code null} for an instant, such as a cache access
 * @param severity {@code OK}, {@code SLOW}, {@code WARN}, or {@code ERROR}
 * @param thread the thread it ran on, or {@code null}
 * @param threadKind the kind of that thread, such as {@code WORKER} or {@code EVENT_LOOP}, or {@code null}
 */
public record RequestTimelineItemDto(
        String source,
        String label,
        String detail,
        long offsetMillis,
        Long durationMicros,
        String severity,
        String thread,
        String threadKind) {}
