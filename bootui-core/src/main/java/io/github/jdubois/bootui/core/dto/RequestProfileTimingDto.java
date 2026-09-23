package io.github.jdubois.bootui.core.dto;

/**
 * Coarse timing breakdown for a profiled request.
 *
 * @param totalMs total request wall-clock time in milliseconds, or {@code null} when unknown
 * @param sqlMs summed duration of the correlated SQL statements, in fractional milliseconds summed from
 *     the statements' microsecond-resolution durations
 * @param sqlCount number of correlated SQL statements
 * @param sqlPercent percentage of the total request time spent in SQL, or {@code null}
 */
public record RequestProfileTimingDto(Long totalMs, double sqlMs, int sqlCount, Double sqlPercent) {}
