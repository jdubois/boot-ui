package io.github.jdubois.bootui.engine.sqltrace;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded cache of what the journal's readers derive from a statement's text: its fingerprint
 * ({@link SqlStatementNormalizer#fingerprintOf}) and the tables it names ({@link SqlTables#of}). The journal's
 * aggregates, Runtime Insights, the runtime model, and request profiles read the same statements again and again, and
 * the journal's dictionary already shares one copy of each statement, so the cache keys on that text
 * ({@code docs/PLAN-v2.md} §5.4).
 *
 * <p>It is read on the journal's dispatcher and on report reads, never on the capture path. It holds at most
 * {@value #MAX_ENTRIES} statements and starts over once full, so unparameterized SQL cannot grow it, and the journal
 * clears it when a run ends or the recording is cleared. Both values are pure functions of the text, so a cached
 * value is never stale.</p>
 */
public final class SqlShapes {

    /** The most statements the cache holds before it starts over. */
    public static final int MAX_ENTRIES = 4_096;

    private static final ConcurrentHashMap<String, Shape> SHAPES = new ConcurrentHashMap<>();

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

    /** Forgets every statement, when a run ends or its recording is cleared. */
    public static void clear() {
        SHAPES.clear();
    }

    /** The statements cached now, for tests. */
    static int size() {
        return SHAPES.size();
    }

    private static Shape shape(String sql) {
        Shape shape = SHAPES.get(sql);
        if (shape != null) {
            return shape;
        }
        if (SHAPES.size() >= MAX_ENTRIES) {
            SHAPES.clear();
        }
        return SHAPES.computeIfAbsent(sql, Shape::new);
    }

    /** One statement's derived values; the tables are read on first use. */
    private static final class Shape {

        private final String sql;
        private final String fingerprint;
        private volatile Set<String> tables;

        Shape(String sql) {
            this.sql = sql;
            this.fingerprint = SqlStatementNormalizer.fingerprintOf(sql);
        }

        String fingerprint() {
            return fingerprint;
        }

        Set<String> tables() {
            Set<String> read = tables;
            if (read == null) {
                read = Collections.unmodifiableSet(new LinkedHashSet<>(SqlTables.of(sql)));
                tables = read;
            }
            return read;
        }
    }
}
