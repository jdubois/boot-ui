package io.github.jdubois.bootui.engine.cache;

import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Framework-neutral, in-memory, bounded ring buffer of cache accesses (hit/miss/put/evict/clear), feeding
 * the Live Activity panel's {@code CACHE} event type (see {@code docs/features/live-activity.md}). Each adapter feeds
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
public final class CacheActivityRecorder implements RuntimeEventPublisher, MemoryOffloadable, LowFootprintSuspendable {

    private static final int MAX_CAPTURE_MANAGERS = 256;
    private static final AtomicLong CAPTURE_EPOCHS = new AtomicLong();
    private final boolean enabled;
    private final int maxEntries;
    private final LongSupplier epochMillis;
    private final Deque<CacheActivityEvent> events = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CorrelationSource correlation = new CorrelationSource();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
    private volatile boolean instrumentedManager;
    private final Map<String, Long> continuousSince = new LinkedHashMap<>();
    private long captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
    private boolean captureManagersExceeded;

    public CacheActivityRecorder(boolean enabled, int maxEntries) {
        this(enabled, maxEntries, System::currentTimeMillis);
    }

    /** With an epoch-millisecond clock for deterministic capture-window tests. */
    public CacheActivityRecorder(boolean enabled, int maxEntries, LongSupplier epochMillis) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1, maxEntries);
        this.epochMillis = java.util.Objects.requireNonNull(epochMillis);
    }

    /** Whether capture is configured on; low-footprint mode can still suspend it. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether capture is enabled and not suspended for low-footprint mode. */
    public boolean isCapturing() {
        return enabled && !lowFootprintPaused;
    }

    /** Records that an adapter successfully installed capture around at least one cache manager. */
    public void markInstrumentedManager() {
        markInstrumentedManager("");
    }

    /** Marks this manager's first installation in the same epoch-millisecond domain as journal requests. */
    public void markInstrumentedManager(String name) {
        java.util.Objects.requireNonNull(name, "Cache manager name must not be null");
        synchronized (lock) {
            instrumentedManager = true;
            if (captureManagersExceeded) {
                return;
            }
            captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
            if (!continuousSince.containsKey(name) && continuousSince.size() >= MAX_CAPTURE_MANAGERS) {
                captureManagersExceeded = true;
            } else {
                continuousSince.put(name, epochMillis.getAsLong());
            }
        }
    }

    /** One coherent, passive read; never reads a clock, manager, cache, or application callback. */
    public CaptureState captureState() {
        synchronized (lock) {
            return new CaptureState(
                    enabled,
                    lowFootprintPaused,
                    journal != RuntimeEventSink.NONE,
                    captureEpoch,
                    Map.copyOf(continuousSince),
                    captureManagersExceeded);
        }
    }

    public record CaptureState(
            boolean enabled,
            boolean paused,
            boolean journalInstalled,
            long epoch,
            Map<String, Long> continuousSince,
            boolean exceeded) {
        public CaptureState {
            continuousSince = Map.copyOf(continuousSince);
        }
    }

    /** Whether this recorder can receive events from at least one instrumented cache manager. */
    public boolean hasInstrumentedManager() {
        return instrumentedManager;
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded access right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        RuntimeEventSink replacement = journal == null ? RuntimeEventSink.NONE : journal;
        synchronized (lock) {
            if (this.journal != replacement) {
                long now = epochMillis.getAsLong();
                continuousSince.replaceAll((name, since) -> now);
                captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
                this.journal = replacement;
            }
        }
    }

    /** Replaces correlation binding and starts a new proof window; null restores the thread scope. */
    public void setCorrelationContextProvider(CorrelationContextProvider correlationProvider) {
        synchronized (lock) {
            long now = epochMillis.getAsLong();
            continuousSince.replaceAll((name, since) -> now);
            captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
            correlation.set(correlationProvider);
        }
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
        if (!isCapturing()) {
            return;
        }
        try {
            CorrelationSource.Read ownership = correlation.read();
            if (ownership.failed()) {
                invalidateCapture(managerName);
            }
            CorrelationContext context = ownership.context();
            CacheActivityEvent event = new CacheActivityEvent(
                    sequence.incrementAndGet(),
                    epochMillis.getAsLong(),
                    managerName,
                    cacheName,
                    operation,
                    key == null ? null : hashKey(key),
                    resolveTraceId(),
                    Thread.currentThread().getName(),
                    context.requestId(),
                    context.executionId());
            synchronized (lock) {
                events.addLast(event);
                while (events.size() > maxEntries) {
                    events.removeFirst();
                }
            }
            RuntimeEventSink sink = journal;
            // Walk the stack only when the journal records cache accesses, since the cache panel needs no frames.
            boolean toJournal = sink.records(JournalSource.CACHE);
            ApplicationFrames frames = toJournal ? ApplicationFrames.capture() : null;
            // The accessing thread's innermost instrumented method, when the BootUI agent records code paths (§5.14).
            long codePathStamp = toJournal ? AgentCodePaths.stamp() : 0L;
            sink.offer(RuntimeEvent.of(
                    JournalSource.CACHE,
                    event.timestampMillis(),
                    -1,
                    context,
                    event.traceId(),
                    event.thread(),
                    null,
                    false,
                    new CachePayload(cacheName, operation.name(), frames, codePathStamp)));
            notifyListeners();
        } catch (RuntimeException ex) {
            invalidateCapture(managerName);
        }
    }

    private void invalidateCapture(String managerName) {
        synchronized (lock) {
            if (continuousSince.containsKey(managerName)) {
                // No finite request start can prove continuous capture across this failed observation.
                continuousSince.put(managerName, Long.MAX_VALUE);
            }
            captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
        }
    }

    /** Returns a defensive, newest-last snapshot of the currently retained events. */
    public List<CacheActivityEvent> recentEvents() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public String offloadId() {
        return "cache-activity";
    }

    @Override
    public String offloadLabel() {
        return "Cache operations";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = recentEvents().size();
        clear();
        return retained;
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
            String traceId = correlation.traceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
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

    private volatile boolean lowFootprintPaused;

    @Override
    public String suspendId() {
        return "cache";
    }

    @Override
    public String suspendLabel() {
        return "Cache activity capture";
    }

    /** Stops recording and drops what is retained, for low-footprint mode. */
    @Override
    public void suspendForLowFootprint() {
        synchronized (lock) {
            if (!lowFootprintPaused) {
                lowFootprintPaused = true;
                captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
            }
        }
        offloadRetainedData();
    }

    @Override
    public void resumeFromLowFootprint() {
        synchronized (lock) {
            if (lowFootprintPaused) {
                long now = epochMillis.getAsLong();
                continuousSince.replaceAll((name, since) -> now);
                captureEpoch = CAPTURE_EPOCHS.incrementAndGet();
                lowFootprintPaused = false;
            }
        }
    }
}
