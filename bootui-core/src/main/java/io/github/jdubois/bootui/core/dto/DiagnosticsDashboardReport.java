package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level payload of the Diagnostics dashboard, which correlates the diagnostic signal panels
 * (HTTP Exchanges, SQL Trace, Exceptions, Security Logs, Traces and Log Tail) into per-request
 * timelines.
 *
 * <p>Correlation is best-effort: it is strongest when distributed tracing (Micrometer Tracing /
 * OTLP) is active and a {@code traceId} is present on the captured signals, and degrades to
 * thread-and-time heuristics otherwise.
 *
 * @param available whether the dashboard could be rendered at all
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param tracingActive whether a trace id was observed on any signal (drives the precision hint)
 * @param sources which signal sources are currently contributing
 * @param totalRequests total number of correlated requests before paging
 * @param requests the correlated requests, most recent first (already filtered and paged)
 * @param unattributed signals that could not be tied to any request
 */
public record DiagnosticsDashboardReport(
        boolean available,
        String unavailableReason,
        boolean tracingActive,
        DiagnosticsSourcesDto sources,
        int totalRequests,
        List<DiagnosticsRequestDto> requests,
        DiagnosticsUnattributedDto unattributed) {

    public DiagnosticsDashboardReport {
        requests = requests == null ? List.of() : List.copyOf(requests);
        unattributed = unattributed == null ? DiagnosticsUnattributedDto.empty() : unattributed;
    }

    public static DiagnosticsDashboardReport unavailable(String reason) {
        return new DiagnosticsDashboardReport(
                false,
                reason,
                false,
                new DiagnosticsSourcesDto(false, false, false, false, false, false),
                0,
                List.of(),
                DiagnosticsUnattributedDto.empty());
    }
}
