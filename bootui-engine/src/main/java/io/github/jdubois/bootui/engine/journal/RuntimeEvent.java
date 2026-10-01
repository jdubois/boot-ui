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
 * @param source the source that recorded it
 * @param epochMillis when it happened, used only to order events across sources and to display them
 * @param durationNanos how long it took, from {@link System#nanoTime()}, or {@code -1} when it has no duration
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
        CorrelationContext correlation = context == null ? CorrelationContext.NONE : context;
        return new RuntimeEvent(
                source,
                epochMillis,
                durationNanos,
                correlation.requestId(),
                correlation.executionId(),
                correlation.traceId(),
                correlation.spanId(),
                thread,
                threadKind,
                failedOrSlow,
                payload);
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

    /** The bytes the event retains, estimated once when the journal retains it. */
    public int estimatedBytes() {
        long bytes = (long) ENVELOPE_BYTES
                + stringBytes(requestId)
                + stringBytes(executionId)
                + stringBytes(traceId)
                + stringBytes(spanId)
                + stringBytes(thread)
                + (payload == null ? 0 : Math.max(0, payload.estimatedBytes()));
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /** The bytes a string retains, counting one byte per character as compact strings store most text. */
    public static int stringBytes(String value) {
        return value == null ? 0 : STRING_OVERHEAD_BYTES + value.length();
    }
}
