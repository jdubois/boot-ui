package io.github.jdubois.bootui.engine.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultToleranceEventDto;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.journal.FaultTolerancePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, independently bounded buffer of recently captured fault tolerance events.
 *
 * <p>The Spring adapter feeds this from Resilience4j's native event publishers and a composed Spring Retry
 * {@code RetryListener}; the Quarkus adapter feeds it from SmallRye Fault Tolerance's
 * {@code CircuitBreakerMaintenance} state-change callback. The recorder itself imports no fault tolerance
 * library type, so an application without any of them never links one.</p>
 *
 * <p><strong>Only metadata is captured.</strong> Method arguments, return values, payloads and raw
 * exception messages never reach this buffer: a failure is reduced to its exception's simple class name,
 * and every free-text field is truncated. The buffer is sized independently of Live Activity's other
 * sources, so fault tolerance traffic can never evict unrelated entries.</p>
 *
 * <p>Capture is fail-open: {@link #record} swallows its own failures so a protected call is never
 * disrupted by BootUI, and it becomes an immediate no-op when capture is disabled.</p>
 */
public final class FaultToleranceEventRecorder implements RuntimeEventPublisher, MemoryOffloadable {

    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    /** Hard cap on any captured free-text metadata value. */
    static final int MAX_METADATA_LENGTH = 200;

    /** Absolute ceiling on the buffer, independent of what an adapter configures. */
    static final int MAX_BUFFER_SIZE = 2_000;

    /** One captured event, before it is flattened to the stable {@link FaultToleranceEventDto} contract. */
    public record CapturedEvent(
            long id,
            long timestamp,
            String policyName,
            String policyType,
            String provider,
            String target,
            String outcome,
            Integer attempt,
            Long durationMillis,
            String failureCategory,
            String state,
            String traceId,
            String requestId) {

        /** Without BootUI's request identity. */
        public CapturedEvent(
                long id,
                long timestamp,
                String policyName,
                String policyType,
                String provider,
                String target,
                String outcome,
                Integer attempt,
                Long durationMillis,
                String failureCategory,
                String state,
                String traceId) {
            this(
                    id,
                    timestamp,
                    policyName,
                    policyType,
                    provider,
                    target,
                    outcome,
                    attempt,
                    durationMillis,
                    failureCategory,
                    state,
                    traceId,
                    null);
        }
    }

    private final boolean enabled;
    private final int maxEntries;

    private final Deque<CapturedEvent> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CorrelationSource correlation = new CorrelationSource();

    public FaultToleranceEventRecorder(boolean enabled, int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.min(MAX_BUFFER_SIZE, Math.max(1, maxEntries));
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each captured outcome, without the
     * failure's message.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * Replaces the source of the request id stamped on each capture ({@code docs/PLAN-v2.md} §5.1). Defaults to the
     * thread's correlation scope; the Quarkus adapter installs one that reads the request's Vert.x context. Passing
     * {@code null} restores the default.
     */
    public void setCorrelationContextProvider(CorrelationContextProvider correlationProvider) {
        correlation.set(correlationProvider);
    }

    /**
     * Captures one fault tolerance event. A {@code null}/blank policy name or outcome is rejected rather than
     * recorded as a misleading blank row, and any runtime failure is swallowed so the protected call the
     * host library is executing is never disrupted.
     */
    public void record(
            String policyName,
            String policyType,
            String provider,
            String target,
            String outcome,
            Integer attempt,
            Long durationMillis,
            String failureCategory) {
        record(
                policyName,
                policyType,
                provider,
                target,
                outcome,
                attempt,
                durationMillis == null ? null : RuntimeEvent.millisToNanos(durationMillis),
                failureCategory,
                null);
    }

    /**
     * Captures one fault tolerance event whose duration the library reports at nanosecond precision, such as a
     * circuit breaker's elapsed call time: the panel keeps milliseconds, and the runtime journal the nanoseconds
     * ({@code docs/PLAN-v2.md} §5.2).
     */
    public void recordNanos(
            String policyName,
            String policyType,
            String provider,
            String target,
            String outcome,
            Integer attempt,
            Long durationNanos,
            String failureCategory) {
        record(policyName, policyType, provider, target, outcome, attempt, durationNanos, failureCategory, null);
    }

    /**
     * Captures one circuit-breaker state transition. The destination state travels in its own field rather
     * than being squeezed into the failure category, so a transition is never mistaken for a failure.
     */
    public void recordStateTransition(String policyName, String provider, String target, String state) {
        record(
                policyName,
                FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                provider,
                target,
                FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION,
                null,
                null,
                null,
                state);
    }

    private void record(
            String policyName,
            String policyType,
            String provider,
            String target,
            String outcome,
            Integer attempt,
            Long durationNanos,
            String failureCategory,
            String state) {
        if (!enabled) {
            return;
        }
        try {
            if (isBlank(policyName) || isBlank(outcome)) {
                return;
            }
            CorrelationContext context = correlation.current();
            CapturedEvent event = new CapturedEvent(
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    truncate(policyName),
                    truncate(policyType),
                    truncate(provider),
                    truncate(target),
                    truncate(outcome),
                    attempt == null ? null : Math.max(1, attempt),
                    durationNanos == null ? null : Math.max(0L, durationNanos) / 1_000_000,
                    truncate(failureCategory),
                    truncate(state),
                    currentTraceId(),
                    context.requestId());
            synchronized (lock) {
                buffer.addLast(event);
                if (buffer.size() > maxEntries) {
                    buffer.removeFirst();
                }
            }
            totalCaptured.incrementAndGet();
            long nanos = durationNanos == null ? -1 : Math.max(0L, durationNanos);
            // A retry's duration is the wait before its next attempt, which starts now; any other duration has
            // elapsed when the library reports it, so the event started that long ago.
            boolean elapsed = !FaultToleranceVocabulary.OUTCOME_RETRY.equals(event.outcome());
            journal.offer(RuntimeEvent.of(
                    JournalSource.FAULT_TOLERANCE,
                    elapsed ? RuntimeEvent.startMillis(event.timestamp(), nanos) : event.timestamp(),
                    nanos,
                    context,
                    event.traceId(),
                    Thread.currentThread().getName(),
                    null,
                    FaultToleranceVocabulary.isFailureOutcome(event.outcome()),
                    new FaultTolerancePayload(
                            event.policyName(),
                            event.policyType(),
                            event.target(),
                            event.outcome(),
                            event.attempt(),
                            event.state(),
                            event.failureCategory(),
                            FaultToleranceVocabulary.isFailureOutcome(event.outcome()),
                            FaultToleranceVocabulary.isProtectiveOutcome(event.outcome()))));
            notifyListeners();
        } catch (RuntimeException ignored) {
            // Capture is strictly pass-through: a BootUI failure must never break a protected call.
        }
    }

    /** The captured events, newest first. */
    public List<CapturedEvent> recent() {
        synchronized (lock) {
            List<CapturedEvent> snapshot = new ArrayList<>(buffer);
            Collections.reverse(snapshot);
            return snapshot;
        }
    }

    /**
     * The captured events, newest first, already flattened to the stable contract and capped at
     * {@code limit} ({@code 0} or negative means no extra cap beyond the buffer itself).
     */
    public List<FaultToleranceEventDto> recentDtos(int limit) {
        List<CapturedEvent> events = recent();
        int cap = limit <= 0 ? events.size() : Math.min(limit, events.size());
        List<FaultToleranceEventDto> dtos = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            dtos.add(toDto(events.get(i)));
        }
        return List.copyOf(dtos);
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    @Override
    public String offloadId() {
        return "fault-tolerance";
    }

    @Override
    public String offloadLabel() {
        return "Fault tolerance events";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = recent().size();
        clear();
        return retained;
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
        notifyListeners();
    }

    /** Subscribes to buffer changes for the Live Activity stream; the returned {@link Runnable} unsubscribes. */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    static FaultToleranceEventDto toDto(CapturedEvent event) {
        return new FaultToleranceEventDto(
                "fault-tolerance-" + event.id(),
                event.timestamp(),
                event.policyName(),
                event.policyType(),
                event.provider(),
                event.target(),
                event.outcome(),
                event.attempt(),
                event.durationMillis(),
                event.failureCategory(),
                event.state(),
                event.traceId());
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A stream subscriber must never disrupt a protected call.
            }
        }
    }

    private String currentTraceId() {
        try {
            String traceId = correlation.traceId();
            return traceId == null || traceId.isBlank() ? null : truncate(traceId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_METADATA_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_METADATA_LENGTH);
    }
}
