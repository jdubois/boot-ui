package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.spi.IdleReclaimable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capped, thread-safe ring buffer of {@link CapturedSecurityEvent} records — the Quarkus capture source
 * for the Security Logs panel. Spring keeps Actuator's {@code AuditEventRepository} as its source of
 * truth, so this buffer is wired only on Quarkus, where there is no Actuator analogue.
 *
 * <p>Writes (from Quarkus CDI security-event observers, often on the Vert.x event loop) and the read
 * snapshot are serialized under a single short lock; masking and DTO assembly happen outside the lock in
 * {@link SecurityLogsService} so the event loop is never blocked. The buffer is {@link IdleReclaimable}:
 * an adapter idle tracker can {@link #suspendForIdle()} to drop retained data and stop recording while
 * the console is unused, then {@link #resumeFromIdle()} to refill from live events.
 */
public final class SecurityEventBuffer implements IdleReclaimable {

    private final int capacity;
    private final ArrayDeque<CapturedSecurityEvent> entries;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean recording = true;

    public SecurityEventBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    /** Records a captured event, evicting the oldest when at capacity. No-op while suspended. */
    public void record(CapturedSecurityEvent event) {
        if (!recording || event == null) {
            return;
        }
        synchronized (entries) {
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(event);
        }
        notifyListeners();
    }

    /** Newest-first immutable snapshot, matching Actuator's reverse-chronological ordering. */
    public List<CapturedSecurityEvent> snapshot() {
        List<CapturedSecurityEvent> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        List<CapturedSecurityEvent> reversed = new ArrayList<>(copy.size());
        for (int i = copy.size() - 1; i >= 0; i--) {
            reversed.add(copy.get(i));
        }
        return List.copyOf(reversed);
    }

    @Override
    public void suspendForIdle() {
        recording = false;
        synchronized (entries) {
            entries.clear();
        }
    }

    @Override
    public void resumeFromIdle() {
        recording = true;
    }

    /**
     * Registers a listener invoked (with no payload) whenever a new event is recorded. Returns a handle
     * that removes the listener when run. Listener failures are isolated so one bad SSE subscriber cannot
     * break security-event capture. Suspend/resume do not notify.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt security-event capture.
            }
        }
    }
}
