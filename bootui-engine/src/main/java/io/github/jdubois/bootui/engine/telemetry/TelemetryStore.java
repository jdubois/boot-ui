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
    private final LinkedHashMap<String, MutableTraceBucket> tracesById;

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

    private static final String LOCAL_SCOPE = "bootui.explorer";

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

    /** Cheap eligibility check; reservations repeat it under the write lock. */
    public boolean acceptsLocalSpans(String traceId) {
        lock.readLock().lock();
        try {
            return settings.enabled() && !idleSuspended && traceId != null && !selfTraceIds.containsKey(traceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reserves before invocation allocation. Keeps up to sixteen slots (at least one, one quarter
     * of small traces) for host/framework spans, including the HTTP root which normally arrives last.
     * Reservations and omission counters live in the existing bounded bucket, not a second index.
     */
    public LocalSpanReservation reserveLocalSpan(String traceId) {
        lock.writeLock().lock();
        try {
            if (!settings.enabled() || idleSuspended || traceId == null || selfTraceIds.containsKey(traceId)) {
                return null;
            }
            MutableTraceBucket bucket = tracesById.get(traceId);
            int capacity = effectiveMaxSpansPerTrace();
            int hostReserve = Math.min(16, Math.max(1, capacity / 4));
            if (bucket == null) {
                bucket = new MutableTraceBucket(traceId);
                evictForNewTrace();
                tracesById.put(traceId, bucket);
            }
            if (bucket.localCalls >= 100 || bucket.spans.size() + bucket.localReservations >= capacity - hostReserve) {
                return null;
            }
            bucket.localCalls++;
            bucket.localReservations++;
            return new LocalSpanReservation(bucket);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Saturating omission counter; never creates a bucket merely to count dropped work. */
    public void omitLocalSpan(String traceId) {
        lock.writeLock().lock();
        try {
            MutableTraceBucket bucket = tracesById.get(traceId);
            if (bucket != null) {
                bucket.omitLocalSpan();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Completes a local reservation without exporting or reviving a cleared/evicted trace. Passing
     * null abandons it (for example when policy changed during the invocation).
     */
    public boolean completeLocalSpan(LocalSpanReservation reservation, NormalizedSpan span) {
        lock.writeLock().lock();
        try {
            if (reservation == null || reservation.completed) {
                return false;
            }
            reservation.completed = true;
            MutableTraceBucket bucket = reservation.bucket;
            bucket.localReservations--;
            if (span == null
                    || idleSuspended
                    || !settings.enabled()
                    || tracesById.get(bucket.traceId) != bucket
                    || selfTraceIds.containsKey(bucket.traceId)) {
                return false;
            }
            if (!bucket.traceId.equals(span.traceId())
                    || !LOCAL_SCOPE.equals(span.scope())
                    || bucket.spans.size() >= effectiveMaxSpansPerTrace()) {
                bucket.omitLocalSpan();
                return false;
            }
            bucket.spans.add(span);
            bucket.lastUpdateEpochNanos = Math.max(bucket.lastUpdateEpochNanos, span.endEpochNanos());
            tracesById.remove(bucket.traceId);
            tracesById.put(bucket.traceId, bucket);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void evictForNewTrace() {
        while (tracesById.size() >= effectiveMaxTraces()) {
            Iterator<String> iterator = tracesById.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
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
            if (idleSuspended) {
                return false;
            }
            String traceId = span.traceId();
            if (selfSpan) {
                selfTraceIds.put(traceId, Boolean.TRUE);
                tracesById.remove(traceId);
                return false;
            }
            if (selfTraceIds.containsKey(traceId)) {
                return false;
            }
            MutableTraceBucket bucket = tracesById.remove(traceId);
            if (bucket == null) {
                bucket = new MutableTraceBucket(traceId);
                evictForNewTrace();
            }
            // Existing telemetry wins over local detail when a busy trace consumes the reservation.
            if (!LOCAL_SCOPE.equals(span.scope()) && bucket.spans.size() >= effectiveMaxSpansPerTrace()) {
                for (int i = bucket.spans.size() - 1; i >= 0; i--) {
                    if (LOCAL_SCOPE.equals(bucket.spans.get(i).scope())) {
                        bucket.spans.remove(i);
                        bucket.omitLocalSpan();
                        break;
                    }
                }
            }
            boolean stored = bucket.spans.size() < effectiveMaxSpansPerTrace();
            if (stored) {
                bucket.spans.add(span);
            }
            bucket.lastUpdateEpochNanos = Math.max(bucket.lastUpdateEpochNanos, span.endEpochNanos());
            tracesById.put(traceId, bucket);
            return stored;
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
            List<MutableTraceBucket> buckets = tracesById.values().stream()
                    .filter(bucket -> !bucket.spans.isEmpty())
                    .toList();
            int resultSize = limit > 0 ? Math.min(limit, buckets.size()) : buckets.size();
            List<TraceBucket> ordered = new ArrayList<>(resultSize);
            for (int i = buckets.size() - 1; i >= buckets.size() - resultSize; i--) {
                ordered.add(buckets.get(i).snapshot());
            }
            return List.copyOf(ordered);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TraceBucket findTrace(String traceId) {
        lock.readLock().lock();
        try {
            MutableTraceBucket bucket = tracesById.get(traceId);
            return bucket == null || bucket.spans.isEmpty() ? null : bucket.snapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int retainedTraceCount() {
        lock.readLock().lock();
        try {
            return (int) tracesById.values().stream()
                    .filter(bucket -> !bucket.spans.isEmpty())
                    .count();
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
            for (MutableTraceBucket bucket : tracesById.values()) {
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

    /** Immutable snapshot of a single trace. */
    public static final class TraceBucket {

        private final String traceId;

        private final List<NormalizedSpan> spans;

        private final long lastUpdateEpochNanos;

        private final int omittedLocalSpans;

        private TraceBucket(
                String traceId, List<NormalizedSpan> spans, long lastUpdateEpochNanos, int omittedLocalSpans) {
            this.traceId = traceId;
            this.spans = List.copyOf(spans);
            this.lastUpdateEpochNanos = lastUpdateEpochNanos;
            this.omittedLocalSpans = omittedLocalSpans;
        }

        public String traceId() {
            return traceId;
        }

        public List<NormalizedSpan> spans() {
            return spans;
        }

        /** A read projection only; never mutates retention or the original evidence. */
        public TraceBucket filterSpans(java.util.function.Predicate<NormalizedSpan> include) {
            return new TraceBucket(
                    traceId, spans.stream().filter(include).toList(), lastUpdateEpochNanos, omittedLocalSpans);
        }

        public long lastUpdateEpochNanos() {
            return lastUpdateEpochNanos;
        }

        /** Explorer omissions, saturating at Integer.MAX_VALUE; scoped to this retained trace bucket. */
        public int omittedLocalSpans() {
            return omittedLocalSpans;
        }
    }

    /** Opaque, single-use reservation; invalidated by clear, idle suspension, self exclusion or eviction. */
    public static final class LocalSpanReservation {
        private final MutableTraceBucket bucket;
        private boolean completed;

        private LocalSpanReservation(MutableTraceBucket bucket) {
            this.bucket = bucket;
        }
    }

    private static final class MutableTraceBucket {

        private final String traceId;

        private final List<NormalizedSpan> spans = new ArrayList<>();

        private long lastUpdateEpochNanos;

        private int localReservations;
        private int localCalls;
        private int omittedLocalSpans;

        private MutableTraceBucket(String traceId) {
            this.traceId = traceId;
        }

        private TraceBucket snapshot() {
            return new TraceBucket(traceId, spans, lastUpdateEpochNanos, omittedLocalSpans);
        }

        private void omitLocalSpan() {
            if (omittedLocalSpans < Integer.MAX_VALUE) {
                omittedLocalSpans++;
            }
        }
    }
}
