package io.github.jdubois.bootui.core.dto;

/**
 * The bounded window every HTTP route summary figure is computed over.
 *
 * <p>HTTP exchanges are retained in a capped in-memory buffer, so a route summary describes a visible slice
 * of recent traffic, never lifetime or service-level metrics. The window states that slice explicitly so a
 * reader can reconcile every route total against the retained exchanges.</p>
 *
 * @param retainedExchanges exchanges currently held by the exchange buffer, including hidden BootUI traffic
 * @param bufferSize maximum number of exchanges the buffer retains, or {@code null} when the exchange source
 *     does not report it, such as an application-provided Actuator repository
 * @param evicted exchanges dropped from the buffer since startup, or {@code null} when the exchange source
 *     does not count evictions
 * @param hiddenSelfExchanges retained exchanges that are BootUI's own traffic and are left out of the summary
 *     while {@code bootui.monitoring.exclude-self} is on
 * @param summarizedExchanges retained, visible exchanges the summary covers
 * @param timedExchanges summarized exchanges that carry a duration
 * @param oldestTimestamp epoch millis of the oldest summarized exchange, or {@code null} when none is
 * @param newestTimestamp epoch millis of the newest summarized exchange, or {@code null} when none is
 * @param totalDurationMs summed duration of every summarized exchange, the denominator of every share
 */
public record HttpRouteWindowDto(
        int retainedExchanges,
        Integer bufferSize,
        Long evicted,
        int hiddenSelfExchanges,
        int summarizedExchanges,
        int timedExchanges,
        Long oldestTimestamp,
        Long newestTimestamp,
        long totalDurationMs) {

    public static HttpRouteWindowDto empty() {
        return new HttpRouteWindowDto(0, null, null, 0, 0, 0, null, null, 0);
    }
}
