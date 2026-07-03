package io.github.jdubois.bootui.engine.activity;

/**
 * An opaque "load more" pagination boundary: the {@code (timestamp, seq)} of the oldest entry already
 * returned to the caller. Entries are always paged newest-first, so the next page is every entry
 * strictly before this boundary in {@code (timestamp desc, seq desc)} order.
 *
 * <p>The encoded form ({@link #encode()}) is plain text, not a security boundary — it is only meant to
 * survive a browser round-trip as a query parameter. {@link #decode(String)} returns {@code null} for
 * anything it cannot parse, so a stale or tampered cursor degrades to "start from the newest page"
 * rather than failing the request.</p>
 *
 * @param timestamp epoch-millis of the boundary entry
 * @param seq sequence number of the boundary entry within its instance
 */
public record ActivityCursor(long timestamp, long seq) {

    private static final String SEPARATOR = "_";

    public String encode() {
        return timestamp + SEPARATOR + seq;
    }

    public static ActivityCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int separator = cursor.indexOf(SEPARATOR);
        if (separator <= 0 || separator == cursor.length() - 1) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(cursor.substring(0, separator));
            long seq = Long.parseLong(cursor.substring(separator + 1));
            return new ActivityCursor(timestamp, seq);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Whether {@code (timestamp, seq)} is strictly before this cursor in newest-first order. */
    public boolean isAfter(long timestamp, long seq) {
        if (timestamp != this.timestamp) {
            return timestamp < this.timestamp;
        }
        return seq < this.seq;
    }
}
