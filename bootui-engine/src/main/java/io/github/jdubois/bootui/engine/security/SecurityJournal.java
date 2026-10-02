package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.SecurityPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Instant;

/**
 * Publishes a security event to the runtime journal ({@code docs/PLAN-v2.md} §5.2) the same way on every stack: its
 * type, never its principal or data, retained longer when it is a failure or a denial, on the thread that observed it.
 */
public final class SecurityJournal {

    private SecurityJournal() {}

    /**
     * Publishes one security event. Never throws, so publishing never disturbs the security check it observes.
     *
     * @param sink the journal, or {@link RuntimeEventSink#NONE}
     * @param type the event's type, such as {@code AUTHENTICATION_FAILURE}
     * @param timestamp when it happened, or {@code null} for now
     * @param context the correlation where it was observed
     * @param observedTraceId the trace id the adapter read with the event, which wins over {@code context}'s, or
     *     {@code null}
     */
    public static void publish(
            RuntimeEventSink sink, String type, Instant timestamp, CorrelationContext context, String observedTraceId) {
        if (sink == null || sink == RuntimeEventSink.NONE) {
            return;
        }
        try {
            sink.offer(RuntimeEvent.of(
                    JournalSource.SECURITY,
                    timestamp == null ? System.currentTimeMillis() : timestamp.toEpochMilli(),
                    -1,
                    context,
                    observedTraceId,
                    Thread.currentThread().getName(),
                    null,
                    SecurityPayload.isFailure(type),
                    new SecurityPayload(type)));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the security check it observes.
        }
    }
}
