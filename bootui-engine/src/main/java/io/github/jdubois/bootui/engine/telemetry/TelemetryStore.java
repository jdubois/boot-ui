package io.github.jdubois.bootui.engine.telemetry;

import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
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
public class TelemetryStore implements RuntimeEventPublisher {

    static final int HARD_MAX_TRACES = 10_000;

    static final int HARD_MAX_SPANS_PER_TRACE = 1_000;

    /**
     * Upper bound on the number of trace ids remembered as BootUI's own traffic. Spans of a single
     * trace are exported within a short window, so a generous bound keeps a self trace identifiable
     * across export batches without growing without limit.
     */
    static final int SELF_TRACE_MEMORY = 4_096;

    /**
     * Upper bound on the spans remembered between their start and their export with the request or execution that
     * started them. A span is forgotten when it is exported, so only spans in flight or waiting in an exporter's batch
     * count; past the bound the oldest are forgotten, and their AI calls fall back to their trace and time.
     */
    static final int MAX_SPAN_OWNERS = 16_384;

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

    /**
     * The request or execution that started each span still in flight, by trace and span id (a span id is unique only
     * within its trace), read on the thread that started it
     * ({@link #spanStarted}), since the exporter runs later on its own thread ({@code docs/PLAN-v2.md} §5.3).
     */
    private final Map<String, CorrelationContext> spanOwners = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CorrelationContext> eldest) {
            return size() > MAX_SPAN_OWNERS;
        }
    });

    private final CorrelationSource correlation = new CorrelationSource();

    private volatile boolean idleSuspended = false;
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

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
        CorrelationContext owner =
                span.spanId() == null ? null : spanOwners.remove(spanKey(span.traceId(), span.spanId()));
        if (idleSuspended) {
            // The store is suspended while BootUI is idle, but the runtime journal keeps recording AI calls
            // (docs/PLAN-v2.md §5.2), so an application span is still recognized and published without being stored.
            if (publishesWhileIdle(span, selfSpan)) {
                publish(span, owner);
            }
            return false;
        }
        // The store may be suspended while this span waits for the lock, so whether it is published is decided there.
        Outcome outcome = store(span, selfSpan);
        if (outcome != Outcome.DROPPED) {
            publish(span, owner);
        }
        return outcome == Outcome.STORED;
    }

    /**
     * Remembers the request or execution doing the work that starts span {@code spanId} of trace {@code traceId}, read
     * on the starting thread,
     * so the AI call it records once exported nests under it by id ({@code docs/PLAN-v2.md} §5.3). Called by the span
     * processor on every span start; it remembers nothing while capture is off, the journal does not record AI calls,
     * or no request or execution owns the work, and never throws.
     */
    public void spanStarted(String traceId, String spanId) {
        try {
            if (traceId == null || spanId == null || !settings.enabled() || !journal.records(JournalSource.AI)) {
                return;
            }
            CorrelationContext context = correlation.current();
            if (context.bootUi() || (context.requestId() == null && context.executionId() == null)) {
                return;
            }
            spanOwners.put(
                    spanKey(traceId, spanId),
                    CorrelationContext.NONE.withRequestId(context.requestId()).withExecutionId(context.executionId()));
        } catch (RuntimeException ex) {
            // Remembering an owner never disturbs the span it observes.
        }
    }

    /**
     * Installs where {@link #spanStarted} reads the correlation of the work starting a span. The default is the
     * thread's scope; the Quarkus adapter also reads the request's Vert.x context.
     */
    public void setCorrelationContextProvider(CorrelationContextProvider provider) {
        correlation.set(provider);
    }

    private static String spanKey(String traceId, String spanId) {
        return traceId + '/' + spanId;
    }

    /** The spans remembered with their owner now, for tests. */
    int spanOwners() {
        return spanOwners.size();
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.5), which receives each stored AI span's metadata (and,
     * while the store is suspended for idleness, each application AI span's metadata without storing it), linked
     * to its request by trace id. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    /**
     * Publishes {@code span} to the journal when it is an AI call, under the request or execution that started it,
     * or, when that is unknown, with only its trace id, which links it to its request by trace and time.
     */
    private void publish(NormalizedSpan span, CorrelationContext owner) {
        RuntimeEventSink sink = journal;
        if (!sink.records(JournalSource.AI)) {
            return;
        }
        try {
            String operation = AiSpanRecognizer.operation(span);
            if (operation == null) {
                return;
            }
            String model = AiSpanRecognizer.responseModel(span);
            sink.offer(RuntimeEvent.of(
                    JournalSource.AI,
                    span.startEpochNanos() / 1_000_000,
                    span.durationNanos(),
                    (owner == null ? CorrelationContext.NONE : owner).withTrace(span.traceId(), span.spanId()),
                    null,
                    null,
                    span.isError(),
                    new AiPayload(
                            operation,
                            AiSpanRecognizer.provider(span),
                            model == null || model.isBlank() ? AiSpanRecognizer.requestModel(span) : model,
                            AiSpanRecognizer.inputTokens(span),
                            AiSpanRecognizer.outputTokens(span),
                            AiSpanRecognizer.finishReason(span),
                            span.isError())));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the telemetry store.
        }
    }

    /**
     * Whether a span seen while idle-suspended goes to the journal: it records AI calls and the span is not BootUI's
     * own traffic, which is still remembered so the rest of its trace stays out once BootUI resumes.
     */
    private boolean publishesWhileIdle(NormalizedSpan span, boolean selfSpan) {
        // Only an AI span is published, so any other span costs no lock while idle.
        if (!journalTakesWhileIdle(span, selfSpan)) {
            return false;
        }
        lock.writeLock().lock();
        try {
            return publishesWhileIdleLocked(span, selfSpan);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean journalTakesWhileIdle(NormalizedSpan span, boolean selfSpan) {
        return journal.records(JournalSource.AI) && (selfSpan || AiSpanRecognizer.operation(span) != null);
    }

    /** {@link #publishesWhileIdle}'s decision, with the write lock held. */
    private boolean publishesWhileIdleLocked(NormalizedSpan span, boolean selfSpan) {
        if (selfSpan) {
            selfTraceIds.put(span.traceId(), Boolean.TRUE);
            return false;
        }
        return !selfTraceIds.containsKey(span.traceId());
    }

    private Outcome store(NormalizedSpan span, boolean selfSpan) {
        lock.writeLock().lock();
        try {
            if (idleSuspended) {
                return journalTakesWhileIdle(span, selfSpan) && publishesWhileIdleLocked(span, selfSpan)
                        ? Outcome.PUBLISHED
                        : Outcome.DROPPED;
            }
            String traceId = span.traceId();
            if (selfSpan) {
                selfTraceIds.put(traceId, Boolean.TRUE);
                tracesById.remove(traceId);
                return Outcome.DROPPED;
            }
            if (selfTraceIds.containsKey(traceId)) {
                return Outcome.DROPPED;
            }
            MutableTraceBucket bucket = tracesById.remove(traceId);
            if (bucket == null) {
                bucket = new MutableTraceBucket(traceId);
                while (tracesById.size() >= effectiveMaxTraces()) {
                    Iterator<Map.Entry<String, MutableTraceBucket>> it =
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
            tracesById.put(traceId, bucket);
            return Outcome.STORED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** What became of a span: stored and published, only published because the store is suspended, or dropped. */
    private enum Outcome {
        STORED,
        PUBLISHED,
        DROPPED
    }

    /**
     * @return the most recently updated traces first.
     */
    public List<TraceBucket> recentTraces(int limit) {
        lock.readLock().lock();
        try {
            List<MutableTraceBucket> buckets = new ArrayList<>(tracesById.values());
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
            return bucket == null ? null : bucket.snapshot();
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

        private TraceBucket(String traceId, List<NormalizedSpan> spans) {
            this.traceId = traceId;
            this.spans = List.copyOf(spans);
        }

        public String traceId() {
            return traceId;
        }

        public List<NormalizedSpan> spans() {
            return spans;
        }
    }

    private static final class MutableTraceBucket {

        private final String traceId;

        private final List<NormalizedSpan> spans = new ArrayList<>();

        private MutableTraceBucket(String traceId) {
            this.traceId = traceId;
        }

        private TraceBucket snapshot() {
            return new TraceBucket(traceId, spans);
        }
    }
}
