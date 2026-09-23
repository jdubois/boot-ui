package io.github.jdubois.bootui.engine.sqltrace;

/**
 * Shared percentage arithmetic for the SQL Trace rankings, so a route's share, a statement's share and a
 * bucket's share are all computed and rounded identically and therefore reconcile against each other.
 */
final class SqlShares {

    private SqlShares() {}

    /**
     * {@code part} as a percentage of {@code total} — both in microseconds, the unit every SQL Trace
     * aggregate accumulates in — rounded to two decimals. Returns {@code 0} when the
     * window holds no measurable database time, which is the honest answer: with a zero denominator no
     * share exists, and reporting anything else would invent one.
     */
    static double percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(10000.0 * part / total) / 100.0;
    }
}
