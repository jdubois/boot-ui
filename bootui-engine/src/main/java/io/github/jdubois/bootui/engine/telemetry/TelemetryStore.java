package io.github.jdubois.bootui.engine.telemetry;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded in-memory store for telemetry spans captured by BootUI.
 *
 * <p>Spans are grouped by trace id. When the configured trace capacity is
 * exceeded the oldest trace bucket is dropped. Each trace also caps its span
 * list to avoid unbounded growth from misbehaving exporters.</p>
 *
 * <p>The store is protected by a {@link ReentrantReadWriteLock} for better
 * scalability on reads, while a {@link LinkedHashMap}
 * ordered by last update time handles capacity bounding.</p>
 *
 * <p>This class is framework-neutral: it reads its capacity bounds through the
 * {@link TelemetrySettings} seam and exposes {@link #suspendForIdle()} /
 * {@link #resumeFromIdle()} as plain methods so an adapter can bridge them to
 * its own idle-reclaim mechanism without coupling the engine to it.</p>
 */
public class TelemetryStore {

    static final int HARD_MAX_TRACES = 10_000;

    static final int HARD_MAX_SPANS_PER_TRACE = 1_000;

    /**
     * Upper bound on the number of trace ids remembered as BootUI's own traffic. Spans of a single
     * trace are exported within a short window, so a generous bound keeps a self trace identifiable
     * across export batches without growing without limit.
     */
    static final int SELF_TRACE_MEMORY = 4_096;

    private final TelemetrySettings settings;
    private final LinkedHashMap<String, TraceBucket> tracesById;

    /**
     * Trace ids known to belong to BootUI's own API traffic. A trace is remembered here the first
     * time any of its spans is classified as a self span (for example the path-bearing HTTP server
     * span for {@code /bootui/api/**}). Sibling spans of the same trace that carry no path
     * attribute &mdash; such as Spring Security {@code security filterchain before/after}
     * observations &mdash; are then dropped as well, even when they are exported in an earlier batch
     * than the root span that identifies the trace.
     */
    private final LinkedHashMap<String, Boolean> selfTraceIds;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile boolean idleSuspended = false;

    public TelemetryStore(TelemetrySettings settings) {
        this.settings = settings;
        this.tracesById = new LinkedHashMap<>(256, 0.75f, false);
        this.selfTraceIds = new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > SELF_TRACE_MEMORY;
            }
        };
    }

    private int effectiveMaxTraces() {
        return clamp(settings.maxTraces(), 1, HARD_MAX_TRACES);
    }

    private int effectiveMaxSpansPerTrace() {
        return clamp(settings.maxSpansPerTrace(), 1, HARD_MAX_SPANS_PER_TRACE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Append a span to its trace, evicting the eldest trace when capacity is reached.
     */
    public void add(NormalizedSpan span) {
        add(span, false);
    }

    /**
     * Append a span to its trace unless the span (or any sibling already seen) marks the trace as
     * BootUI's own traffic.
     *
     * @param span the normalized span to store
     * @param selfSpan {@code true} when the caller has classified this span as BootUI's own (for
     *     example because it carries a {@code /bootui/**} path); the whole trace is then dropped and
     *     remembered so its remaining spans are dropped too
     * @return {@code true} when the span was stored, {@code false} when it was dropped
     */
    public boolean add(NormalizedSpan span, boolean selfSpan) {
        if (span == null || span.traceId() == null || span.traceId().isEmpty()) {
            return false;
        }
        if (idleSuspended) {
            return false;
        }
        lock.writeLock().lock();
        try {
            String traceId = span.traceId();
            if (selfSpan) {
                selfTraceIds.put(traceId, Boolean.TRUE);
                tracesById.remove(traceId);
                return false;
            }
            if (selfTraceIds.containsKey(traceId)) {
                return false;
            }
            TraceBucket bucket = tracesById.remove(traceId);
            if (bucket == null) {
                bucket = new TraceBucket(traceId);
                while (tracesById.size() >= effectiveMaxTraces()) {
                    Iterator<Map.Entry<String, TraceBucket>> it =
                            tracesById.entrySet().iterator();
                    if (!it.hasNext()) {
                        break;
                    }
                    it.next();
                    it.remove();
                }
            }
            if (bucket.spans.size() < effectiveMaxSpansPerTrace()) {
                bucket.spans.add(span);
            }
            bucket.lastUpdateEpochNanos = Math.max(bucket.lastUpdateEpochNanos, span.endEpochNanos());
            tracesById.put(traceId, bucket);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @return the most recently updated traces first.
     */
    public List<TraceBucket> recentTraces(int limit) {
        lock.readLock().lock();
        try {
            List<TraceBucket> ordered = new ArrayList<>(tracesById.values());
            Collections.reverse(ordered);
            if (limit > 0 && ordered.size() > limit) {
                return new ArrayList<>(ordered.subList(0, limit));
            }
            return ordered;
        } finally {
            lock.readLock().unlock();
        }
    }

    public TraceBucket findTrace(String traceId) {
        lock.readLock().lock();
        try {
            return tracesById.get(traceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int retainedTraceCount() {
        lock.readLock().lock();
        try {
            return tracesById.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int capacity() {
        return effectiveMaxTraces();
    }

    /**
     * Snapshot of all spans for read-only iteration.
     */
    public List<NormalizedSpan> allSpansSnapshot() {
        List<NormalizedSpan> out = new ArrayList<>();
        lock.readLock().lock();
        try {
            for (TraceBucket bucket : tracesById.values()) {
                out.addAll(bucket.spans);
            }
        } finally {
            lock.readLock().unlock();
        }
        return out;
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            tracesById.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Stop ingesting and release retained spans while the console is idle. Bridged to the adapter's
     * idle-reclaim mechanism (for example a Spring {@code IdleReclaimable} bean) by the adapter.
     */
    public void suspendForIdle() {
        idleSuspended = true;
        lock.writeLock().lock();
        try {
            tracesById.clear();
            selfTraceIds.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Resume ingestion after {@link #suspendForIdle()}. */
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Holder for a single trace; the spans list is mutated under the store's monitor.
     */
    public static final class TraceBucket {

        private final String traceId;

        private final List<NormalizedSpan> spans;

        private long lastUpdateEpochNanos;

        TraceBucket(String traceId) {
            this.traceId = traceId;
            this.spans = new ArrayList<>();
        }

        public String traceId() {
            return traceId;
        }

        public List<NormalizedSpan> spans() {
            return spans;
        }

        public long lastUpdateEpochNanos() {
            return lastUpdateEpochNanos;
        }
    }
}
