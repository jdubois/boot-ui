package io.github.jdubois.bootui.engine.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultToleranceEventDto;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.TraceIdProvider;
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
public final class FaultToleranceEventRecorder {

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

    private volatile TraceIdProvider traceIdProvider = FaultToleranceEventRecorder::mdcTraceId;
    private final CorrelationSource correlation = new CorrelationSource();

    public FaultToleranceEventRecorder(boolean enabled, int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.min(MAX_BUFFER_SIZE, Math.max(1, maxEntries));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * Replaces the trace-id source. The Spring adapter keeps the SLF4J MDC default that Micrometer Tracing
     * publishes; the Quarkus adapter supplies an OpenTelemetry-backed provider because its requests hop
     * between the event loop and worker threads.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider == null ? FaultToleranceEventRecorder::mdcTraceId : traceIdProvider;
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
        record(policyName, policyType, provider, target, outcome, attempt, durationMillis, failureCategory, null);
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
            Long durationMillis,
            String failureCategory,
            String state) {
        if (!enabled) {
            return;
        }
        try {
            if (isBlank(policyName) || isBlank(outcome)) {
                return;
            }
            CapturedEvent event = new CapturedEvent(
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    truncate(policyName),
                    truncate(policyType),
                    truncate(provider),
                    truncate(target),
                    truncate(outcome),
                    attempt == null ? null : Math.max(1, attempt),
                    durationMillis == null ? null : Math.max(0L, durationMillis),
                    truncate(failureCategory),
                    truncate(state),
                    currentTraceId(),
                    correlation.requestId());
            synchronized (lock) {
                buffer.addLast(event);
                if (buffer.size() > maxEntries) {
                    buffer.removeFirst();
                }
            }
            totalCaptured.incrementAndGet();
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
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : truncate(traceId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Default trace-id source: the SLF4J MDC key Micrometer Tracing publishes on Spring. */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException | LinkageError ex) {
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
