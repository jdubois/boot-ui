package io.github.jdubois.bootui.engine.cache;

import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, in-memory, bounded ring buffer of cache accesses (hit/miss/put/evict/clear), feeding
 * the Live Activity panel's {@code CACHE} event type (see {@code docs/PLAN.md} §3.4). Each adapter feeds
 * this recorder from wherever it intercepts real cache access — the Spring adapter decorates
 * {@code CacheManager}/{@code Cache} beans so every access (annotation-driven or programmatic) is
 * captured — while this class owns only the neutral concerns: bounding, key hashing, and change
 * notification.
 *
 * <p>Never records raw keys or values: only a short, stable {@link #hashKey(Object)} digest is kept, so a
 * captured event carries no application data even under full value exposure.</p>
 *
 * <p>All retained data lives only in memory, is bounded by {@code maxEntries}, and is reset on application
 * restart or via {@link #clear()}. Thread-safe: {@link #record} may be called concurrently from many
 * application threads while {@link #recentEvents()} is read from an HTTP request thread.</p>
 */
public final class CacheActivityRecorder {

    private final boolean enabled;
    private final int maxEntries;
    private final Deque<CacheActivityEvent> events = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile TraceIdProvider traceIdProvider = CacheActivityRecorder::mdcTraceId;

    public CacheActivityRecorder(boolean enabled, int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** Whether this recorder is capturing new events; {@code false} disables recording entirely. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Installs the {@link TraceIdProvider} used to stamp the active distributed-trace id on each captured
     * event. Defaults to the SLF4J MDC {@code traceId} key (the same default {@code SqlTraceRecorder}
     * uses); each adapter may install its own (e.g. an OpenTelemetry-backed one).
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider == null ? CacheActivityRecorder::mdcTraceId : traceIdProvider;
    }

    /** Records a cache read that found a value. */
    public void recordHit(String managerName, String cacheName, Object key) {
        record(managerName, cacheName, CacheActivityOperation.HIT, key);
    }

    /** Records a cache read that found no value. */
    public void recordMiss(String managerName, String cacheName, Object key) {
        record(managerName, cacheName, CacheActivityOperation.MISS, key);
    }

    /** Records a cache write. */
    public void recordPut(String managerName, String cacheName, Object key) {
        record(managerName, cacheName, CacheActivityOperation.PUT, key);
    }

    /** Records a single-key invalidation. */
    public void recordEvict(String managerName, String cacheName, Object key) {
        record(managerName, cacheName, CacheActivityOperation.EVICT, key);
    }

    /** Records a whole-cache invalidation (no single key involved). */
    public void recordClear(String managerName, String cacheName) {
        record(managerName, cacheName, CacheActivityOperation.CLEAR, null);
    }

    private void record(String managerName, String cacheName, CacheActivityOperation operation, Object key) {
        if (!enabled) {
            return;
        }
        try {
            CacheActivityEvent event = new CacheActivityEvent(
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    managerName,
                    cacheName,
                    operation,
                    key == null ? null : hashKey(key),
                    resolveTraceId(),
                    Thread.currentThread().getName());
            synchronized (lock) {
                events.addLast(event);
                while (events.size() > maxEntries) {
                    events.removeFirst();
                }
            }
            notifyListeners();
        } catch (RuntimeException ex) {
            // Recording must never disrupt the cache access it observes.
        }
    }

    /** Returns a defensive, newest-last snapshot of the currently retained events. */
    public List<CacheActivityEvent> recentEvents() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    /** Clears all retained events. */
    public void clear() {
        synchronized (lock) {
            events.clear();
        }
    }

    /**
     * Subscribes to a signal fired whenever a new event is recorded, letting the Live Activity SSE stream
     * push a refresh tick. Returns an unsubscribe callback.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                // A misbehaving stream subscriber must never disrupt cache access.
            }
        }
    }

    private String resolveTraceId() {
        try {
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Default trace-id source: the SLF4J MDC where Micrometer Tracing publishes it (mirrors
     * {@code SqlTraceRecorder}'s default so cache accesses correlate the same way SQL statements do).
     */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException | NoClassDefFoundError ex) {
            return null;
        }
    }

    /** Short, stable, non-reversible digest of a cache key so raw keys never leave the process. */
    static String hashKey(Object key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
