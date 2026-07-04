package io.github.jdubois.bootui.engine.activity;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link ActivityStore} whose backing delegate can be swapped at runtime, so every long-lived
 * consumer (the capture coordinator, the panel controller/resource) can hold one stable reference —
 * injected once as the shared bean/CDI singleton — while the "Use a database" runtime switch
 * (see {@code ActivitySwitchService}) replaces what it delegates to underneath them.
 *
 * <p>{@link ActivityStoreFactory#create} always returns one of these, wrapping either a bare
 * {@link InMemoryActivityStore} (persistence disabled at startup) or a {@link BufferedActivityStore}
 * (persistence enabled at startup) as the initial delegate.
 *
 * <p>Every {@link ActivityStore} method is explicitly overridden to delegate — deliberately not relying
 * on the interface's default no-op {@link ActivityStore#prune} / {@link ActivityStore#close}, which
 * would otherwise silently swallow pruning and, worse, the bounded final-flush-on-shutdown guarantee
 * {@link BufferedActivityStore#close()} provides once a runtime switch has made a durable store the
 * active delegate.
 */
public final class SwitchableActivityStore implements ActivityStore {

    private final AtomicReference<ActivityStore> delegate;

    public SwitchableActivityStore(ActivityStore initialDelegate) {
        this.delegate = new AtomicReference<>(initialDelegate);
    }

    @Override
    public void appendBatch(List<StoredActivityEntry> entries) {
        delegate.get().appendBatch(entries);
    }

    @Override
    public ActivityPage query(ActivityQuery query) {
        return delegate.get().query(query);
    }

    @Override
    public List<StoredActivityEntry> queryByCorrelationId(String correlationId, int limit) {
        return delegate.get().queryByCorrelationId(correlationId, limit);
    }

    @Override
    public void prune(String instanceId, long olderThanEpochMillis) {
        delegate.get().prune(instanceId, olderThanEpochMillis);
    }

    @Override
    public void close() {
        delegate.get().close();
    }

    /** Whether the current delegate is a durable {@link BufferedActivityStore}, as opposed to the plain in-memory default. */
    public boolean persistent() {
        return delegate.get() instanceof BufferedActivityStore;
    }

    /**
     * Atomically switches to {@code replacement} unless persistence is already active, guarding against
     * a double-switch race (for example two concurrent "Use the existing datasource" requests). Returns
     * {@code true} if this call performed the switch, {@code false} if persistence was already active
     * (a no-op) — the caller must close {@code replacement} itself in the latter case to avoid leaking
     * its flush scheduler thread, since this method never takes ownership of a replacement it rejects.
     */
    public synchronized boolean attemptSwitchToPersistent(ActivityStore replacement) {
        if (persistent()) {
            return false;
        }
        delegate.set(replacement);
        return true;
    }

    /**
     * The current delegate. Package-private: production code should route through the {@link
     * ActivityStore} methods above or {@link #persistent()}; this exists only so same-package tests can
     * assert on the concrete delegate type/behavior without reaching into private state.
     */
    ActivityStore delegate() {
        return delegate.get();
    }
}
