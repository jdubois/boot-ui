package io.github.jdubois.bootui.core.dto;

/**
 * Bounded runtime counters for one fault tolerance policy.
 *
 * <p>Every field is nullable on purpose: a library that does not expose a counter reports {@code null}
 * rather than a guessed {@code 0}, so the UI can render "not reported" honestly. Counters are read from
 * the library's own registry or metrics; BootUI never derives them by wrapping application beans.</p>
 *
 * @param successfulCalls successful protected calls, or {@code null} when not reported
 * @param failedCalls failed protected calls, or {@code null} when not reported
 * @param retriedCalls calls that were retried at least once, or {@code null} when not reported
 * @param rejectedCalls calls rejected before execution (rate limiter or bulkhead), or {@code null}
 * @param timeoutCalls calls that exceeded their time limit, or {@code null} when not reported
 * @param shortCircuitedCalls calls rejected by an open circuit breaker, or {@code null}
 * @param failureRatePercent current failure rate percentage, or {@code null} when not reported (a
 *     negative library sentinel meaning "not enough samples yet" is normalized to {@code null})
 * @param bufferedCalls calls currently held in the library's sliding window, or {@code null}
 */
public record FaultTolerancePolicyMetricsDto(
        Long successfulCalls,
        Long failedCalls,
        Long retriedCalls,
        Long rejectedCalls,
        Long timeoutCalls,
        Long shortCircuitedCalls,
        Double failureRatePercent,
        Long bufferedCalls) {

    /** A metrics block with no counter reported at all. */
    public static FaultTolerancePolicyMetricsDto none() {
        return new FaultTolerancePolicyMetricsDto(null, null, null, null, null, null, null, null);
    }
}
