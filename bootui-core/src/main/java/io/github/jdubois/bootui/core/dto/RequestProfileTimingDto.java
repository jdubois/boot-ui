package io.github.jdubois.bootui.core.dto;

/**
 * Coarse timing breakdown for a profiled request.
 *
 * <p>Every figure counts all correlated children, including any a bounded section did not show.
 * Outbound calls may overlap, so their summed time can exceed the request's own duration.</p>
 *
 * @param totalMs total request wall-clock time in milliseconds, or {@code null} when unknown
 * @param sqlMs summed duration of the correlated SQL statements, in fractional milliseconds summed from
 *     the statements' microsecond-resolution durations
 * @param sqlCount number of correlated SQL statements
 * @param sqlPercent percentage of the total request time spent in SQL, or {@code null}
 * @param restCallCount number of correlated outbound REST client calls
 * @param restCallMs summed duration of the correlated outbound REST client calls, in milliseconds
 */
public record RequestProfileTimingDto(
        Long totalMs, double sqlMs, int sqlCount, Double sqlPercent, int restCallCount, long restCallMs) {

    /** The original SQL-only timing shape. */
    public RequestProfileTimingDto(Long totalMs, double sqlMs, int sqlCount, Double sqlPercent) {
        this(totalMs, sqlMs, sqlCount, sqlPercent, 0, 0L);
    }
}
