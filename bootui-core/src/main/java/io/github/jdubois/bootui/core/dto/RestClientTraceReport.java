package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level REST client trace report returned by the REST Client panel.
 *
 * @param available whether at least one Spring HTTP client (RestClient, RestTemplate, or WebClient) has
 *     been instrumented
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param capturing whether new calls are currently being recorded (runtime pause/resume)
 * @param captureHeaders whether request headers are being captured
 * @param bufferSize maximum number of calls retained in memory
 * @param totalCaptured total calls seen since startup (may exceed buffer)
 * @param slowCallThresholdMillis threshold above which a call is "slow"
 * @param clientTypes which Spring HTTP clients have made at least one instrumented call
 * @param stats aggregate counters over the retained buffer
 * @param entries the retained calls, most recent first
 * @param topCalls calls grouped by method/host/normalized path, most frequent first
 * @param warnings non-fatal advisories about the current trace state
 * @param retention how the buffer retains calls, including its reserved share for failed or slow
 *     calls and its eviction count, or {@code null} when tracing is unavailable
 */
public record RestClientTraceReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        boolean captureHeaders,
        int bufferSize,
        long totalCaptured,
        long slowCallThresholdMillis,
        List<String> clientTypes,
        RestClientTraceStatsDto stats,
        List<RestClientTraceEntryDto> entries,
        List<RestClientTraceGroupDto> topCalls,
        List<String> warnings,
        CaptureRetentionDto retention) {

    public RestClientTraceReport {
        clientTypes = DtoCollections.immutableCopy(clientTypes);
        entries = DtoCollections.immutableCopy(entries);
        topCalls = DtoCollections.immutableCopy(topCalls);
        warnings = DtoCollections.immutableCopy(warnings);
    }

    /** A report without retention counts, for callers that assemble entries from no known buffer. */
    public RestClientTraceReport(
            boolean available,
            String unavailableReason,
            boolean capturing,
            boolean captureHeaders,
            int bufferSize,
            long totalCaptured,
            long slowCallThresholdMillis,
            List<String> clientTypes,
            RestClientTraceStatsDto stats,
            List<RestClientTraceEntryDto> entries,
            List<RestClientTraceGroupDto> topCalls,
            List<String> warnings) {
        this(
                available,
                unavailableReason,
                capturing,
                captureHeaders,
                bufferSize,
                totalCaptured,
                slowCallThresholdMillis,
                clientTypes,
                stats,
                entries,
                topCalls,
                warnings,
                null);
    }

    public static RestClientTraceReport unavailable(String reason) {
        return new RestClientTraceReport(
                false,
                reason,
                false,
                false,
                0,
                0,
                0,
                List.of(),
                RestClientTraceStatsDto.empty(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }
}
