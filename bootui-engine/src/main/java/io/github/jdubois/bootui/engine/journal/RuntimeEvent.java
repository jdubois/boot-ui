package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.web.CorrelationTier;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.Objects;

/**
 * One runtime fact, as a recorder publishes it to the journal ({@code docs/PLAN-v2.md} §5.2): its source, when it
 * happened, how long it took, the request, execution, trace, and span it belongs to, the thread it ran on, and a small
 * immutable payload.
 *
 * <p>The journal adds the identity: its instance, its run, and a sequence number unique within that run
 * ({@link JournalEntry}). Recorders build events on the application thread, so an event holds only references the
 * recorder already has.</p>
 *
 * <p>Clocks ({@code docs/PLAN-v2.md} §5.2): {@code epochMillis} is when the work <em>started</em>, for every source,
 * so an event with a duration covers {@code [epochMillis, epochMillis + durationNanos)}. A recorder that only learns
 * of the work when it completes stamps its start as the completion time minus the duration
 * ({@link #startMillis}). The wall clock only orders and displays events; {@code durationNanos} comes from
 * {@link System#nanoTime()} and is nanosecond-precise wherever the source measures it. A few sources only know
 * milliseconds, such as garbage collections, scheduled runs on Quarkus, and fault-tolerance events, whose libraries
 * report a millisecond or {@link java.time.Duration} elapsed time; their durations are whole milliseconds. Monotonic
 * times that observations order work by, such as {@link SqlPayload#completedNanos()}, live in the payloads and keep
 * their own meaning.</p>
 *
 * <p>Recorders build events with {@link #of}, from the {@link CorrelationContext} they captured, so the request,
 * execution, trace, and span ids are filled the same way for every source.</p>
 *
 * @param source the source that recorded it
 * @param epochMillis when it started, used only to order events across sources and to display them
 * @param durationNanos how long it took, from {@link System#nanoTime()} where measured, or {@code -1} when it has no
 *     duration
 * @param requestId the BootUI request it belongs to, or {@code null}
 * @param executionId the scheduled run or consumed message it belongs to, or {@code null}
 * @param traceId its distributed-trace id, or {@code null}
 * @param spanId its span id, or {@code null}
 * @param thread the name of the thread it ran on, or {@code null}
 * @param threadKind the kind of that thread, or {@code null} when unknown
 * @param failedOrSlow whether it failed or crossed its slow threshold, which the journal retains longer
 * @param payload its source-specific part, or {@code null}
 */
public record RuntimeEvent(
        JournalSource source,
        long epochMillis,
        long durationNanos,
        String requestId,
        String executionId,
        String traceId,
        String spanId,
        String thread,
        ThreadKind threadKind,
        boolean failedOrSlow,
        RuntimeEventPayload payload) {

    /** Bytes an event and its journal entry retain before their strings and payload, on a 64-bit JVM. */
    static final int ENVELOPE_BYTES = 120;

    /** Bytes a {@link String} retains beyond its characters. */
    static final int STRING_OVERHEAD_BYTES = 40;

    public RuntimeEvent {
        Objects.requireNonNull(source, "source must not be null");
        if (!source.publishesEvents()) {
            throw new IllegalArgumentException("The " + source.propertyName() + " source publishes no events");
        }
    }

    /** An event correlated with {@code context}, the correlation current where the recorder captured it. */
    public static RuntimeEvent of(
            JournalSource source,
            long epochMillis,
            long durationNanos,
            CorrelationContext context,
            String thread,
            ThreadKind threadKind,
            boolean failedOrSlow,
            RuntimeEventPayload payload) {
        return of(source, epochMillis, durationNanos, context, null, thread, threadKind, failedOrSlow, payload);
    }

    /**
     * An event correlated with {@code context}, whose trace id is {@code observedTraceId}, the one the recorder read
     * with the work it observes, or the context's when the recorder read none. The recorder's is read with the work, so
     * it wins over a context opened earlier.
     */
    public static RuntimeEvent of(
            JournalSource source,
            long epochMillis,
            long durationNanos,
            CorrelationContext context,
            String observedTraceId,
            String thread,
            ThreadKind threadKind,
            boolean failedOrSlow,
            RuntimeEventPayload payload) {
        CorrelationContext correlation = context == null ? CorrelationContext.NONE : context;
        String observed = blankToNull(observedTraceId);
        String traceId = observed != null ? observed : correlation.traceId();
        return new RuntimeEvent(
                source,
                epochMillis,
                durationNanos,
                correlation.requestId(),
                correlation.executionId(),
                traceId,
                correlation.spanId(),
                thread,
                threadKind,
                failedOrSlow,
                payload);
    }

    /**
     * When work that completed at {@code completedEpochMillis} after {@code durationNanos} started: the completion time
     * minus the duration, for recorders that only learn of the work when it completes. A negative duration, meaning
     * none, leaves the completion time.
     */
    public static long startMillis(long completedEpochMillis, long durationNanos) {
        return durationNanos <= 0 ? completedEpochMillis : completedEpochMillis - durationNanos / 1_000_000L;
    }

    /** {@code millis} as nanoseconds, or {@code -1} when it is {@code null}, for sources that only know milliseconds. */
    public static long millisToNanos(Long millis) {
        return millis == null ? -1 : Math.max(0L, millis) * 1_000_000L;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** This event with {@code replacement} as its payload. */
    public RuntimeEvent withPayload(RuntimeEventPayload replacement) {
        return new RuntimeEvent(
                source,
                epochMillis,
                durationNanos,
                requestId,
                executionId,
                traceId,
                spanId,
                thread,
                threadKind,
                failedOrSlow,
                replacement);
    }

    /** This event with {@code kind} as the kind of its thread. */
    public RuntimeEvent withThreadKind(ThreadKind kind) {
        return new RuntimeEvent(
                source,
                epochMillis,
                durationNanos,
                requestId,
                executionId,
                traceId,
                spanId,
                thread,
                kind,
                failedOrSlow,
                payload);
    }

    /**
     * How the event knows the work it belongs to: {@link CorrelationTier#REQUEST_ID} when it carries a request or
     * execution id, {@link CorrelationTier#TRACE_ID} when it carries only a trace id, and {@code null} otherwise.
     */
    public CorrelationTier correlationTier() {
        if (requestId != null || executionId != null) {
            return CorrelationTier.REQUEST_ID;
        }
        return traceId != null ? CorrelationTier.TRACE_ID : null;
    }

    /** The bytes the event retains when none of its payload's strings are shared. */
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /**
     * The bytes the event retains, estimated once when the journal retains it, counting each payload string that
     * {@code dictionary} shares as a reference ({@link RuntimeEventPayload#estimatedBytes(JournalDictionary)}).
     */
    public int estimatedBytes(JournalDictionary dictionary) {
        int payloadBytes = payload == null
                ? 0
                : (dictionary == null ? payload.estimatedBytes() : payload.estimatedBytes(dictionary));
        long bytes = (long) ENVELOPE_BYTES
                + stringBytes(requestId)
                + stringBytes(executionId)
                + stringBytes(traceId)
                + stringBytes(spanId)
                + stringBytes(thread)
                + Math.max(0, payloadBytes);
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /** The bytes a string retains, counting one byte per character as compact strings store most text. */
    public static int stringBytes(String value) {
        return value == null ? 0 : STRING_OVERHEAD_BYTES + value.length();
    }
}
