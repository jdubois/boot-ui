package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A correlated unit of work in the Diagnostics dashboard — typically one HTTP request/trace, but it
 * may also be a background trace or a burst of activity grouped by thread when no trace context is
 * available.
 *
 * <p>Each request carries the merged, time-ordered {@link DiagnosticsTimelineEntryDto timeline} of
 * the SQL executions, exceptions, security events, log lines and spans attributed to it, so a user
 * can pivot from any one signal to the request that produced it.
 *
 * @param id stable identifier for the request (the trace id when present, otherwise a synthetic
 *     key)
 * @param correlation how the signals were grouped: {@code TRACE} (joined by trace id — strong),
 *     {@code REQUEST} (matched to an HTTP exchange heuristically), {@code THREAD} (grouped by thread
 *     and time window — best-effort) or {@code SINGLE} (a lone signal)
 * @param traceId the shared trace id, when known; {@code null} otherwise
 * @param method HTTP method of the anchoring request, when known
 * @param path request path of the anchoring request, when known
 * @param status HTTP status of the anchoring request, when known
 * @param durationMs duration of the anchoring request/trace in milliseconds, when known
 * @param principal already-masked principal of the anchoring request, when known
 * @param startTimestamp earliest event time in the timeline, in epoch milliseconds
 * @param label human-friendly headline (e.g. {@code POST /orders}, a span name, or a thread name)
 * @param httpCount number of HTTP exchange events attributed to the request
 * @param sqlCount number of SQL executions attributed to the request
 * @param exceptionCount number of exceptions attributed to the request
 * @param securityCount number of security events attributed to the request
 * @param logCount number of log lines attributed to the request
 * @param hasError whether any error-severity signal is present
 * @param timeline the merged, time-ordered events
 */
public record DiagnosticsRequestDto(
        String id,
        String correlation,
        String traceId,
        String method,
        String path,
        Integer status,
        Long durationMs,
        String principal,
        long startTimestamp,
        String label,
        int httpCount,
        int sqlCount,
        int exceptionCount,
        int securityCount,
        int logCount,
        boolean hasError,
        List<DiagnosticsTimelineEntryDto> timeline) {

    public DiagnosticsRequestDto {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }
}
