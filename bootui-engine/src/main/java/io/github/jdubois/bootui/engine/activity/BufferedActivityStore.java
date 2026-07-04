package io.github.jdubois.bootui.engine.activity;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator composing a hot {@link InMemoryActivityStore} with any durable {@link ActivityStore} (in
 * practice, a {@link JdbcActivityStore}) to add write-behind buffering, scheduled flush,
 * merge-for-reads, and re-queueing on flush failure. This is the composition seam behind the design's
 * "swappable storage" requirement: any future durable backend just needs to implement {@link
 * ActivityStore} and can be wrapped here unchanged.
 *
 * <ul>
 *   <li><strong>Buffering + scheduled flush.</strong> Every {@link #appendBatch} both updates the hot
 *       cache immediately and queues the entries for their first durable write; a background task
 *       drains that queue into the durable store every {@code flushInterval}.
 *   <li><strong>Merge-for-reads.</strong> {@link #query} always reads both the hot cache and the durable
 *       store and merges the results, so an entry appended moments ago is visible immediately even
 *       before its scheduled flush — and stays visible from the fast in-memory cache afterward too.
 *   <li><strong>Re-queue on failure.</strong> If a flush attempt throws (the database is unreachable,
 *       for example), the whole failed batch is put back at the <em>front</em> of the pending queue —
 *       ahead of anything captured in the meantime — so it is retried on the next tick rather than lost.
 *   <li><strong>Bounded pending queue.</strong> During a prolonged durable-storage outage the pending
 *       queue is capped; once full, the oldest pending entry is dropped (with a one-time-per-episode
 *       warning) rather than growing without bound. Dropped entries remain visible from the hot cache
 *       for as long as they stay in it — they are simply never persisted.
 * </ul>
 *
 * <p>Not idle-reclaimable by design: unlike BootUI's other live buffers, the pending-flush queue must
 * never be silently cleared, or captured entries would be lost without ever reaching durable storage. A
 * future enhancement could reclaim just the hot cache (never the pending queue) on idle.</p>
 *
 * <p><strong>Shutdown.</strong> {@link #close()} makes one bounded, best-effort attempt to flush
 * whatever is still pending before stopping the scheduler, so entries captured just before an
 * application stops are not routinely lost on every restart (see {@link #awaitFinalFlush()}). The wait
 * is capped at {@link #closeFlushTimeoutMillis} so a database that is slow or unreachable at shutdown
 * time can never block application shutdown indefinitely; if the cap is hit, whatever is still pending
 * is logged and left in memory only.</p>
 */
public final class BufferedActivityStore implements ActivityStore {

    private static final System.Logger log = System.getLogger(BufferedActivityStore.class.getName());

    /**
     * Floor for {@link #closeFlushTimeoutMillis}: even an aggressively short configured flush interval
     * gets at least this much grace on shutdown, since a real JDBC round-trip needs more than a few
     * milliseconds even when the database is healthy.
     */
    private static final long MIN_CLOSE_FLUSH_TIMEOUT_MILLIS = 2000;

    /**
     * Ceiling for {@link #closeFlushTimeoutMillis}: even a deliberately long configured flush interval
     * cannot make shutdown wait indefinitely, protecting the host application's own termination grace
     * period budget (for example Kubernetes' {@code terminationGracePeriodSeconds}).
     */
    private static final long MAX_CLOSE_FLUSH_TIMEOUT_MILLIS = 10_000;

    private final InMemoryActivityStore hotCache;
    private final ActivityStore durable;
    private final int maxPendingEntries;
    private final Deque<StoredActivityEntry> pendingFlush = new ArrayDeque<>();
    private final Object pendingLock = new Object();
    private final ScheduledExecutorService scheduler;
    private final long closeFlushTimeoutMillis;

    public BufferedActivityStore(
            InMemoryActivityStore hotCache, ActivityStore durable, Duration flushInterval, int maxPendingEntries) {
        this(hotCache, durable, flushInterval, maxPendingEntries, null, null);
    }

    /**
     * Full constructor additionally scheduling retention pruning: every ten flush cycles (a coarser
     * cadence than the flush itself, since pruning is comparatively expensive and does not need to run
     * as often), {@code durable.prune(instanceId, now - retention)} deletes this instance's own
     * expired rows. Pruning is skipped entirely when {@code instanceId} or {@code retention} is
     * {@code null}.
     */
    public BufferedActivityStore(
            InMemoryActivityStore hotCache,
            ActivityStore durable,
            Duration flushInterval,
            int maxPendingEntries,
            String instanceId,
            Duration retention) {
        this.hotCache = hotCache;
        this.durable = durable;
        this.maxPendingEntries = Math.max(1, maxPendingEntries);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bootui-activity-flush");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1, flushInterval == null ? 5000 : flushInterval.toMillis());
        this.closeFlushTimeoutMillis =
                Math.min(Math.max(intervalMillis, MIN_CLOSE_FLUSH_TIMEOUT_MILLIS), MAX_CLOSE_FLUSH_TIMEOUT_MILLIS);
        scheduler.scheduleWithFixedDelay(this::safeFlush, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        if (instanceId != null && retention != null && !retention.isNegative() && !retention.isZero()) {
            long pruneIntervalMillis = intervalMillis * 10;
            scheduler.scheduleWithFixedDelay(
                    () -> safePrune(instanceId, retention),
                    pruneIntervalMillis,
                    pruneIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void appendBatch(List<StoredActivityEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        hotCache.appendBatch(entries);
        synchronized (pendingLock) {
            pendingFlush.addAll(entries);
            trimPendingIfNeeded();
        }
    }

    @Override
    public ActivityPage query(ActivityQuery query) {
        // Fetching (pageSize + 1) from each side is provably enough to determine the correct top
        // (pageSize + 1) of the merged union: the k-way merge argument for two already-sorted-desc
        // sources — the true (pageSize + 1)-th combined entry cannot rank lower than (pageSize + 1)
        // within either individual source.
        ActivityQuery subQuery = query.withPageSize(query.pageSize() + 1);
        ActivityPage hotPage = hotCache.query(subQuery);
        ActivityPage durablePage = durable.query(subQuery);

        List<StoredActivityEntry> merged =
                new ArrayList<>(hotPage.entries().size() + durablePage.entries().size());
        merged.addAll(hotPage.entries());
        merged.addAll(durablePage.entries());
        merged.sort(
                Comparator.comparingLong((StoredActivityEntry s) -> s.entry().timestamp())
                        .thenComparingLong(StoredActivityEntry::seq)
                        .reversed());

        List<StoredActivityEntry> deduped = new ArrayList<>(merged.size());
        Set<String> seen = new HashSet<>();
        for (StoredActivityEntry entry : merged) {
            if (seen.add(entry.instanceId() + '#' + entry.seq())) {
                deduped.add(entry);
            }
        }

        boolean hasMore = deduped.size() > query.pageSize();
        List<StoredActivityEntry> page = hasMore ? deduped.subList(0, query.pageSize()) : deduped;
        String nextCursor = null;
        if (hasMore) {
            StoredActivityEntry last = page.get(page.size() - 1);
            nextCursor = new ActivityCursor(last.entry().timestamp(), last.seq()).encode();
        }
        return new ActivityPage(page, nextCursor, hasMore);
    }

    @Override
    public List<StoredActivityEntry> queryByCorrelationId(String correlationId, int limit) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        // Same merge-for-reads rationale as query(): an entry captured moments ago may still only be in
        // the hot cache (not yet flushed), so both sides must be consulted and deduped.
        List<StoredActivityEntry> hot = hotCache.queryByCorrelationId(correlationId, limit);
        List<StoredActivityEntry> fromDurable = durable.queryByCorrelationId(correlationId, limit);

        List<StoredActivityEntry> merged = new ArrayList<>(hot.size() + fromDurable.size());
        merged.addAll(hot);
        merged.addAll(fromDurable);
        merged.sort(
                Comparator.comparingLong((StoredActivityEntry s) -> s.entry().timestamp())
                        .thenComparingLong(StoredActivityEntry::seq)
                        .reversed());

        List<StoredActivityEntry> deduped = new ArrayList<>(merged.size());
        Set<String> seen = new HashSet<>();
        for (StoredActivityEntry entry : merged) {
            if (seen.add(entry.instanceId() + '#' + entry.seq()) && deduped.size() < limit) {
                deduped.add(entry);
            }
        }
        return deduped;
    }

    /** Number of entries currently awaiting their first durable-write attempt. Exposed for tests/diagnostics. */
    public int pendingCount() {
        synchronized (pendingLock) {
            return pendingFlush.size();
        }
    }

    /** Runs one flush cycle synchronously (used by tests and available for a future manual "flush now" action). */
    public void flushNow() {
        safeFlush();
    }

    private void safeFlush() {
        try {
            flush();
        } catch (Throwable ex) {
            // A scheduled task that throws stops all future executions on a ScheduledExecutorService, so
            // this must never propagate. flush() already narrows failures from the durable store into a
            // requeue; this is a last-resort safety net.
            log.log(Level.WARNING, "Unexpected failure while flushing Live Activity entries", ex);
        }
    }

    private void safePrune(String instanceId, Duration retention) {
        try {
            durable.prune(instanceId, System.currentTimeMillis() - retention.toMillis());
        } catch (Throwable ex) {
            // Same rationale as safeFlush(): a scheduled task must never throw, and pruning is best-effort
            // housekeeping — a failed prune simply retries on the next cycle.
            log.log(Level.WARNING, "Unexpected failure while pruning Live Activity entries", ex);
        }
    }

    private void flush() {
        List<StoredActivityEntry> batch;
        synchronized (pendingLock) {
            if (pendingFlush.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingFlush);
            pendingFlush.clear();
        }
        try {
            durable.appendBatch(batch);
        } catch (RuntimeException ex) {
            log.log(
                    Level.WARNING,
                    "Live Activity flush to durable storage failed; re-queueing " + batch.size() + " entries",
                    ex);
            synchronized (pendingLock) {
                // Put the failed batch back ahead of anything captured while the attempt was in flight, so
                // it is retried first and chronological order is preserved once flushing succeeds again.
                List<StoredActivityEntry> combined = new ArrayList<>(batch.size() + pendingFlush.size());
                combined.addAll(batch);
                combined.addAll(pendingFlush);
                pendingFlush.clear();
                pendingFlush.addAll(combined);
                trimPendingIfNeeded();
            }
        }
    }

    private void trimPendingIfNeeded() {
        int dropped = 0;
        while (pendingFlush.size() > maxPendingEntries) {
            pendingFlush.removeFirst();
            dropped++;
        }
        if (dropped > 0) {
            log.log(
                    Level.WARNING,
                    "Live Activity pending-flush buffer exceeded " + maxPendingEntries
                            + " entries during a durable-storage outage; dropped " + dropped
                            + " oldest entries (still visible from the in-memory cache, but will not be persisted)");
        }
    }

    @Override
    public void close() {
        awaitFinalFlush();
        scheduler.shutdownNow();
        try {
            durable.close();
        } catch (Exception ignored) {
            // Best-effort: closing must never throw during shutdown.
        }
    }

    /**
     * Makes one last attempt to flush whatever is still pending, bounded by
     * {@link #closeFlushTimeoutMillis} so a database that is slow or unreachable at shutdown time can
     * never block application shutdown indefinitely. The attempt is submitted to the same
     * single-threaded scheduler used for periodic flushes, so it naturally queues behind (rather than
     * races) a flush already in progress. If the bound is exceeded, whatever is still pending is
     * logged and left in memory only — lost once this store is closed — rather than waiting longer;
     * the scheduler's thread is a daemon thread, so a JDBC call still stuck past the bound cannot
     * prevent the JVM from exiting either.
     */
    private void awaitFinalFlush() {
        if (pendingCount() == 0) {
            return; // nothing pending right now; skip the round-trip entirely (best-effort, not exact)
        }
        try {
            Future<?> lastFlush = scheduler.submit(this::safeFlush);
            lastFlush.get(closeFlushTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            log.log(
                    Level.WARNING,
                    "Live Activity final flush on shutdown did not complete within " + closeFlushTimeoutMillis + "ms; "
                            + pendingCount() + " pending entries will not be persisted");
        } catch (Exception ex) {
            // Covers RejectedExecutionException (close() called more than once) and any other
            // unexpected failure: shutdown must never be blocked or fail because the final flush
            // could not be attempted.
            log.log(
                    Level.WARNING,
                    "Live Activity final flush on shutdown failed; pending entries will not be persisted",
                    ex);
        }
    }
}
