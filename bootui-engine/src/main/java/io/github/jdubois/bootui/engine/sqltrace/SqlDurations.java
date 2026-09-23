package io.github.jdubois.bootui.engine.sqltrace;

/**
 * Conversions between the microsecond resolution SQL Trace records and the fractional milliseconds it
 * reports.
 *
 * <p>Statements are timed with {@code System.nanoTime()} and recorded in microseconds, because an ordinary
 * primary-key {@code SELECT} against a local database finishes well inside one millisecond: truncating at
 * the recording seam would make almost every execution contribute {@code 0} and leave the panel with nothing
 * to rank. Aggregation therefore sums microseconds and only converts once, here, at the DTO boundary.</p>
 */
final class SqlDurations {

    /** Decimal places kept when reporting milliseconds, i.e. full microsecond resolution. */
    private static final double MICROS_PER_MILLI = 1_000.0;

    private SqlDurations() {}

    /** The fractional milliseconds an exact microsecond total represents. */
    static double millis(long micros) {
        return micros / MICROS_PER_MILLI;
    }

    /** The fractional milliseconds a microsecond mean represents, rounded to microsecond resolution. */
    static double millis(double micros) {
        return Math.round(micros) / MICROS_PER_MILLI;
    }

    /**
     * The whole milliseconds a microsecond duration rounds to, for the compatibility {@code durationMillis}
     * fields and for consumers whose contract is a whole-millisecond count. Rounds rather than truncates, so
     * a 600 µs statement reads {@code 1 ms} instead of vanishing.
     */
    static long roundedMillis(long micros) {
        return Math.round(millis(micros));
    }
}
