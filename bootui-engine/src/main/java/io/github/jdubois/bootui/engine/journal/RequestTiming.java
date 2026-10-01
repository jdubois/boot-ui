package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RequestPhases;

/**
 * Where a request's time went, as its adapter measured it ({@code docs/PLAN-v2.md} §5.5): its monotonic start, which
 * places its statements, calls, and connection waits in it, the time it spent authenticating, and when its handler and
 * its response write began. Unknown values are {@code -1}.
 *
 * @param startNanos the {@link System#nanoTime()} when it started
 * @param authenticationNanos time spent authenticating it, summed over its authentication intervals
 * @param handlerOffsetNanos when its handler began, from its start
 * @param responseOffsetNanos when its response write or view rendering began, from its start
 */
public record RequestTiming(
        long startNanos, long authenticationNanos, long handlerOffsetNanos, long responseOffsetNanos) {

    /** A request whose phases are unknown, with only its monotonic start. */
    public static RequestTiming startedAt(long startNanos) {
        return new RequestTiming(startNanos, -1, -1, -1);
    }

    /** A request started at {@code startNanos}, with the phases {@code markers} recorded, which may be {@code null}. */
    public static RequestTiming of(long startNanos, RequestPhases.Markers markers) {
        if (markers == null || markers.filtersAt() == null) {
            return startedAt(startNanos);
        }
        long filtersAt = markers.filtersAt();
        return new RequestTiming(
                startNanos,
                Math.max(0, markers.authenticationMicros()) * 1_000,
                offset(filtersAt, markers.handlerAt()),
                offset(filtersAt, markers.responseAt()));
    }

    private static long offset(long filtersAtMicros, Long atMicros) {
        return atMicros == null ? -1 : Math.max(0, atMicros - filtersAtMicros) * 1_000;
    }

    /** Whether its handler and response phases are known. */
    public boolean phased() {
        return handlerOffsetNanos >= 0;
    }
}
