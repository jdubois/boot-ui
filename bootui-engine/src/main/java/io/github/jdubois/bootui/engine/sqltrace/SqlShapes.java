package io.github.jdubois.bootui.engine.sqltrace;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded cache of what the journal derives from a statement's text: its fingerprint
 * ({@link SqlStatementNormalizer#fingerprintOf}), the tables it names ({@link SqlTables#of}), and whether it is worth
 * sharing in the journal's dictionary. The journal's dispatcher, aggregates, Runtime Insights, the runtime model, and
 * request profiles read the same statements again and again, so each is parsed once ({@code docs/PLAN-v2.md} §5.4).
 *
 * <p>It is read on the journal's dispatcher and on report reads, never on the capture path. It holds at most
 * {@value #MAX_ENTRIES} statements and about {@value #MAX_BYTES} bytes, outside the journal's own byte bound, and
 * starts over once either is reached, so unparameterized SQL cannot grow it. A statement longer than
 * {@value #MAX_CACHED_LENGTH} characters is parsed on every read rather than cached. The journal clears it when the
 * recording is cleared, so no cleared statement outlives it, and after a run's last events are processed. Every value
 * is a pure function of the text, so a cached value is never stale.</p>
 */
public final class SqlShapes {

    /** The most statements the cache holds before it starts over. */
    public static final int MAX_ENTRIES = 4_096;

    /** About the most bytes the cached statements and what is derived from them retain before it starts over. */
    public static final long MAX_BYTES = 1_048_576;

    /** The longest statement cached; a longer one is parsed on every read. */
    static final int MAX_CACHED_LENGTH = 8_192;

    private static final ConcurrentHashMap<String, Shape> SHAPES = new ConcurrentHashMap<>();

    /** Guards inserting and clearing, so the bounds hold however many threads read at once. */
    private static final Object LOCK = new Object();

    private static final AtomicLong BYTES = new AtomicLong();

    private SqlShapes() {}

    /** The fingerprint of {@code sql}, as {@link SqlStatementNormalizer#fingerprintOf} computes it. */
    public static String fingerprint(String sql) {
        return sql == null
                ? SqlStatementNormalizer.fingerprintOf(null)
                : shape(sql).fingerprint();
    }

    /** The tables {@code sql} names, as {@link SqlTables#of} reads them; the set is unmodifiable. */
    public static Set<String> tables(String sql) {
        return sql == null ? Set.of() : shape(sql).tables();
    }

    /**
     * Whether the journal's dictionary should share {@code sql}: it has no literal in a comparison, {@code IN} list, or
     * {@code LIKE}, where a value concatenated into the text would make every run of it a one-off string
     * ({@link SqlStatementNormalizer.Result#predicateLiteralCount()}).
     */
    public static boolean shareable(String sql) {
        return sql != null && shape(sql).shareable();
    }

    /** Forgets every statement, when a run ends or its recording is cleared. */
    public static void clear() {
        synchronized (LOCK) {
            SHAPES.clear();
            BYTES.set(0);
        }
    }

    /** The statements cached now, for tests. */
    static int size() {
        return SHAPES.size();
    }

    /** About the bytes the cached statements retain now, for tests. */
    static long bytes() {
        return BYTES.get();
    }

    private static Shape shape(String sql) {
        Shape shape = SHAPES.get(sql);
        if (shape != null) {
            return shape;
        }
        Shape parsed = new Shape(sql);
        if (sql.length() > MAX_CACHED_LENGTH) {
            return parsed;
        }
        synchronized (LOCK) {
            Shape known = SHAPES.get(sql);
            if (known != null) {
                return known;
            }
            if (SHAPES.size() >= MAX_ENTRIES || BYTES.get() + parsed.bytes() > MAX_BYTES) {
                SHAPES.clear();
                BYTES.set(0);
            }
            SHAPES.put(sql, parsed);
            BYTES.addAndGet(parsed.bytes());
            return parsed;
        }
    }

    /** One statement's derived values; the tables are read on first use. */
    private static final class Shape {

        private final String sql;
        private final String fingerprint;
        private final boolean shareable;
        private volatile Set<String> tables;

        Shape(String sql) {
            SqlStatementNormalizer.Result normalized = SqlStatementNormalizer.normalize(sql);
            this.sql = sql;
            this.fingerprint = normalized.fingerprint();
            this.shareable = normalized.predicateLiteralCount() == 0;
        }

        String fingerprint() {
            return fingerprint;
        }

        boolean shareable() {
            return shareable;
        }

        Set<String> tables() {
            Set<String> read = tables;
            if (read == null) {
                read = Collections.unmodifiableSet(new LinkedHashSet<>(SqlTables.of(sql)));
                tables = read;
            }
            return read;
        }

        /**
         * What it retains, counted up front: the statement, its fingerprint, and its tables, which are never longer
         * than the statement, at two bytes a character, plus the objects holding them.
         */
        long bytes() {
            return 2L * (2L * sql.length() + fingerprint.length()) + 128;
        }
    }
}
