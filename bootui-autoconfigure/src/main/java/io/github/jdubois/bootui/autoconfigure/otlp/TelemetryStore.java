package io.github.jdubois.bootui.autoconfigure.otlp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.*;

/**
 * Bounded in-memory store for telemetry spans captured by BootUI.
 *
 * <p>Spans are grouped by trace id. When the configured trace capacity is
 * exceeded the oldest trace bucket is dropped. Each trace also caps its span
 * list to avoid unbounded growth from misbehaving exporters.</p>
 *
 * <p>The store is intentionally simple: a synchronized {@link LinkedHashMap}
 * ordered by last update time. BootUI is a developer-only console and is
 * never expected to ingest production trace volumes.</p>
 */
public class TelemetryStore {

    static final int HARD_MAX_TRACES = 10_000;

    static final int HARD_MAX_SPANS_PER_TRACE = 1_000;
    private final BootUiProperties.Telemetry config;
    private final LinkedHashMap<String, TraceBucket> tracesById;

    public TelemetryStore(BootUiProperties.Telemetry config) {
        this.config = config;
        this.tracesById = new LinkedHashMap<>(256, 0.75f, false);
    }

    static int effectiveMaxTraces(BootUiProperties.Telemetry config) {
        return clamp(config.getMaxTraces(), 1, HARD_MAX_TRACES);
    }

    static int effectiveMaxSpansPerTrace(BootUiProperties.Telemetry config) {
        return clamp(config.getMaxSpansPerTrace(), 1, HARD_MAX_SPANS_PER_TRACE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Append a span to its trace, evicting the eldest trace when capacity is reached.
     */
    public synchronized void add(NormalizedSpan span) {
        if (span == null || span.traceId() == null || span.traceId().isEmpty()) {
            return;
        }
        TraceBucket bucket = tracesById.remove(span.traceId());
        if (bucket == null) {
            bucket = new TraceBucket(span.traceId());
            while (tracesById.size() >= effectiveMaxTraces(config)) {
                Iterator<Map.Entry<String, TraceBucket>> it =
                        tracesById.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
        if (bucket.spans.size() < effectiveMaxSpansPerTrace(config)) {
            bucket.spans.add(span);
        }
        bucket.lastUpdateEpochNanos = Math.max(bucket.lastUpdateEpochNanos, span.endEpochNanos());
        tracesById.put(span.traceId(), bucket);
    }

    /**
     * @return the most recently updated traces first.
     */
    public synchronized List<TraceBucket> recentTraces(int limit) {
        List<TraceBucket> ordered = new ArrayList<>(tracesById.values());
        java.util.Collections.reverse(ordered);
        if (limit > 0 && ordered.size() > limit) {
            return new ArrayList<>(ordered.subList(0, limit));
        }
        return ordered;
    }

    public synchronized TraceBucket findTrace(String traceId) {
        return tracesById.get(traceId);
    }

    public synchronized int retainedTraceCount() {
        return tracesById.size();
    }

    public int capacity() {
        return effectiveMaxTraces(config);
    }

    /**
     * Snapshot of all spans for read-only iteration.
     */
    public synchronized List<NormalizedSpan> allSpansSnapshot() {
        List<NormalizedSpan> out = new ArrayList<>();
        for (TraceBucket bucket : tracesById.values()) {
            out.addAll(bucket.spans);
        }
        return out;
    }

    public synchronized void clear() {
        tracesById.clear();
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
