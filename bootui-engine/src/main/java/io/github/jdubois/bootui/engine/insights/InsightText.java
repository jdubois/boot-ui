package io.github.jdubois.bootui.engine.insights;

import java.util.Locale;

/** Small helpers observations share to word their sentences and keep their ids stable. */
final class InsightText {

    /** The longest statement quoted in a sentence. */
    static final int MAX_QUOTED_SQL = 80;

    private InsightText() {}

    /** {@code count noun}, with {@code noun}'s plural when the count is not one. */
    static String counted(long count, String noun, String plural) {
        return count + " " + (count == 1 ? noun : plural);
    }

    static String counted(long count, String noun) {
        return counted(count, noun, noun + "s");
    }

    /** A statement short enough to quote in a sentence. */
    static String quoted(String sql) {
        String flat = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_QUOTED_SQL ? flat : flat.substring(0, MAX_QUOTED_SQL - 1) + "…";
    }

    /**
     * A short, stable hash of {@code value}, the same in every JVM and run (64-bit FNV-1a), for ids that must survive
     * restarts.
     */
    static String stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash).substring(0, 10);
    }

    /** Milliseconds from nanoseconds, with one decimal below 10 ms. */
    static String millis(long nanos) {
        double ms = nanos / 1_000_000.0;
        return ms < 10 ? String.format(Locale.ROOT, "%.1f", ms) : String.valueOf(Math.round(ms));
    }
}
