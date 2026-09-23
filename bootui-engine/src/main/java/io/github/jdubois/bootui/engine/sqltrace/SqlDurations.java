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
public final class SqlDurations {

    private static final double MICROS_PER_MILLI = 1_000.0;

    private static final long MICROS_PER_MILLI_EXACT = 1_000L;

    private SqlDurations() {}

    /** The fractional milliseconds an exact microsecond total represents. */
    public static double millis(long micros) {
        return micros / MICROS_PER_MILLI;
    }

    /**
     * The fractional milliseconds a microsecond mean represents. Kept exact rather than rounded to a whole
     * microsecond, so a group ranked for a positive mean never reports a mean of {@code 0}.
     */
    public static double millis(double micros) {
        return micros / MICROS_PER_MILLI;
    }

    /**
     * The whole milliseconds a microsecond duration rounds to, for the compatibility {@code durationMillis}
     * fields and for consumers whose contract is a whole-millisecond count. Rounds rather than truncates, so
     * a 600 µs statement reads {@code 1 ms} instead of vanishing.
     */
    public static long roundedMillis(long micros) {
        return Math.round(millis(micros));
    }

    /**
     * The whole milliseconds a microsecond duration covers, rounded up. Used where a duration is subtracted
     * from a millisecond timestamp to reconstruct when an execution started: rounding down there would claim
     * a statement started later than it did and could absorb it into a request window it began before.
     */
    public static long ceilMillis(long micros) {
        if (micros <= 0) {
            return 0;
        }
        // Divide first so values near Long.MAX_VALUE cannot overflow the way (micros + 999) / 1000 would.
        return micros / MICROS_PER_MILLI_EXACT + (micros % MICROS_PER_MILLI_EXACT == 0 ? 0 : 1);
    }
}
