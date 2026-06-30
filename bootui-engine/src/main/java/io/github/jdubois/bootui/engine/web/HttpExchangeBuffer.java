package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.spi.IdleReclaimable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capped, thread-safe ring buffer of {@link CapturedHttpExchange} records — the Quarkus capture source
 * for the HTTP Exchanges and Live Activity panels. Spring keeps Actuator's {@code HttpExchangeRepository}
 * as its source of truth, so this buffer is wired only on Quarkus, where there is no Actuator analogue.
 *
 * <p>Writes (from any number of Vert.x event-loop threads) and the read snapshot are serialized under a
 * single short lock; masking and DTO assembly happen outside the lock in {@link HttpExchangesService} so
 * the event loop is never blocked on expensive work. The buffer is {@link IdleReclaimable}: an adapter
 * idle tracker can {@link #suspendForIdle()} to drop retained data and stop recording while the console
 * is unused, then {@link #resumeFromIdle()} to refill from live traffic.
 */
public final class HttpExchangeBuffer implements IdleReclaimable {

    private final int capacity;
    private final ArrayDeque<CapturedHttpExchange> entries;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean recording = true;

    public HttpExchangeBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    /** Records a completed exchange, evicting the oldest when at capacity. No-op while suspended. */
    public void record(CapturedHttpExchange exchange) {
        if (!recording || exchange == null) {
            return;
        }
        synchronized (entries) {
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(exchange);
        }
        notifyListeners();
    }

    /** Newest-first immutable snapshot, matching Actuator's reverse-chronological ordering. */
    public List<CapturedHttpExchange> snapshot() {
        List<CapturedHttpExchange> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        List<CapturedHttpExchange> reversed = new ArrayList<>(copy.size());
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
     * Registers a listener invoked (with no payload) whenever a new exchange is recorded. Returns a handle
     * that removes the listener when run. Listener failures are isolated so one bad SSE subscriber cannot
     * break HTTP-exchange capture. Suspend/resume do not notify.
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
                // A misbehaving stream subscriber must never disrupt HTTP-exchange capture.
            }
        }
    }
}
