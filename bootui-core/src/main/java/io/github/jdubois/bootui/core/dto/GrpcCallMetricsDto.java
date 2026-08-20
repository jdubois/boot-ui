package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Aggregate call metrics for one gRPC service, method, or client channel.
 *
 * <p>Every value is joined from metrics the application already publishes. BootUI never installs a second
 * interceptor to synthesize them, so {@code available=false} means "no matching metric series exists" and the
 * UI must say so rather than render zeros as if they were measurements.</p>
 *
 * @param available whether any matching metric series was found
 * @param callCount total completed calls, or {@code 0} when unavailable
 * @param activeCalls calls currently in progress, or {@code null} when no in-progress series exists
 * @param totalDurationMs summed call duration in milliseconds, or {@code null} when unavailable
 * @param maxDurationMs longest observed call duration in milliseconds, or {@code null} when unavailable
 * @param averageDurationMs mean call duration in milliseconds, or {@code null} when unavailable
 * @param statusCounts per-status call counts, ordered by descending count then status name
 */
public record GrpcCallMetricsDto(
        boolean available,
        long callCount,
        Long activeCalls,
        Double totalDurationMs,
        Double maxDurationMs,
        Double averageDurationMs,
        List<GrpcStatusCountDto> statusCounts) {

    /** The stable "no native metric series exists" value. */
    public static final GrpcCallMetricsDto UNAVAILABLE =
            new GrpcCallMetricsDto(false, 0L, null, null, null, null, List.of());

    public GrpcCallMetricsDto {
        statusCounts = statusCounts == null ? List.of() : List.copyOf(statusCounts);
    }
}
