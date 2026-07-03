package io.github.jdubois.bootui.engine.activity;

import java.util.concurrent.Callable;

/**
 * Suppresses BootUI's own JDBC statement capture for the duration of a block of code — the "flush
 * guard" that keeps {@link JdbcActivityStore}'s own schema-creation, insert and query statements from
 * being recorded (and, worse, re-captured and re-persisted in an infinite loop) when it runs over a
 * {@code DataSource} that {@code SqlTraceRecorder} is also wrapping.
 *
 * <p>{@code SqlTraceRecorder.record(...)} checks {@link #isSuppressed()} and skips recording when it is
 * {@code true}. Every JDBC call {@link JdbcActivityStore} issues — the table-existence probe, {@code
 * CREATE TABLE}, inserts and queries — runs inside {@link #runSuppressed(Runnable)} (or the checked
 * variant). This also means the store's own read queries (pagination/search) never pollute the very
 * feed being browsed.</p>
 *
 * <p>Backed by a {@link ThreadLocal}, so it only suppresses capture on the thread actually running the
 * store's JDBC call (the flush scheduler thread, or the HTTP request thread serving a query) — it never
 * accidentally suppresses capture of the host application's own concurrent JDBC traffic on other
 * threads.</p>
 */
public final class BootUiJdbcCaptureGuard {

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BootUiJdbcCaptureGuard() {}

    /** Whether the current thread is inside a suppressed block. */
    public static boolean isSuppressed() {
        return SUPPRESSED.get();
    }

    /** Runs {@code action} with capture suppressed on the current thread, always restoring the prior state. */
    public static void runSuppressed(Runnable action) {
        boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            SUPPRESSED.set(previous);
        }
    }

    /** Checked-exception variant of {@link #runSuppressed(Runnable)}. */
    public static <T> T runSuppressed(Callable<T> action) throws Exception {
        boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            return action.call();
        } finally {
            SUPPRESSED.set(previous);
        }
    }
}
