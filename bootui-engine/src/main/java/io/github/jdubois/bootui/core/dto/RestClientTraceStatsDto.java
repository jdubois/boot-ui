package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate counters over the outbound HTTP calls currently retained in the trace buffer.
 *
 * @param totalCalls number of captured calls in the buffer
 * @param totalDurationMillis sum of call times across the buffer
 * @param maxDurationMillis slowest single call time
 * @param avgDurationMillis mean call time
 * @param slowCalls calls over the slow-call threshold
 * @param failedCalls calls where the client threw before a response arrived
 * @param errorStatusCalls calls that received a {@code 4xx}/{@code 5xx} response (the client did not throw)
 * @param getCount {@code GET} calls
 * @param postCount {@code POST} calls
 * @param putCount {@code PUT} calls
 * @param deleteCount {@code DELETE} calls
 * @param otherCount calls of any other method
 * @param evicted calls dropped from the buffer because it reached capacity
 */
public record RestClientTraceStatsDto(
        long totalCalls,
        long totalDurationMillis,
        long maxDurationMillis,
        double avgDurationMillis,
        long slowCalls,
        long failedCalls,
        long errorStatusCalls,
        long getCount,
        long postCount,
        long putCount,
        long deleteCount,
        long otherCount,
        long evicted) {

    public static RestClientTraceStatsDto empty() {
        return new RestClientTraceStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
