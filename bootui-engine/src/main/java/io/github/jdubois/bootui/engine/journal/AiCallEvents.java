package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes the AI calls an AI framework reports itself ({@code docs/PLAN-v2.md} §5.18, M3-9): Spring AI's {@code
 * gen_ai.client.operation} observation and Quarkus LangChain4j's {@code ChatModelListener}. Each call is stamped with
 * the request or execution that made it at capture, so AI usage needs no tracing and covers jobs and listeners.
 *
 * <p>GenAI spans received over OTLP stay the fallback: a call recorded here is remembered by its trace and span id, or by
 * its trace and operation when its span is unknown, and {@link #recordedNatively} tells the telemetry store to skip the
 * span of the same call. Only metadata is kept: never a prompt, completion, or tool argument.</p>
 */
public final class AiCallEvents {

    /** The calls remembered for de-duplication at most; older ones are forgotten first. */
    static final int MAX_REMEMBERED = 4_096;

    private static final Map<String, Boolean> NATIVE = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    private AiCallEvents() {}

    /**
     * Publishes one call, which started at {@code startEpochMillis} and took {@code durationNanos}, for the work {@code
     * context} names, without its monotonic completion.
     *
     * @param traceId the call's trace id, or {@code null} without tracing
     * @param spanId the call's own span id, or {@code null} when unknown
     * @return whether the journal accepted it
     */
    public static boolean publish(
            RuntimeEventSink journal,
            CorrelationContext context,
            String traceId,
            String spanId,
            long startEpochMillis,
            long durationNanos,
            String thread,
            AiPayload payload) {
        return publish(journal, context, traceId, spanId, startEpochMillis, durationNanos, -1, thread, payload);
    }

    /**
     * Publishes one call, which started at {@code startEpochMillis}, took {@code durationNanos}, and completed at the
     * {@link System#nanoTime()} {@code completedNanos}, for the work {@code context} names.
     *
     * @param traceId the call's trace id, or {@code null} without tracing
     * @param spanId the call's own span id, or {@code null} when unknown
     * @param completedNanos the monotonic time the call completed, or {@code -1} when unknown, which places it on its
     *     request's clock ({@code route-time-breakdown})
     * @return whether the journal accepted it
     */
    public static boolean publish(
            RuntimeEventSink journal,
            CorrelationContext context,
            String traceId,
            String spanId,
            long startEpochMillis,
            long durationNanos,
            long completedNanos,
            String thread,
            AiPayload payload) {
        if (journal == null || payload == null || payload.operation() == null || !journal.records(JournalSource.AI)) {
            return false;
        }
        CorrelationContext owner = context == null ? CorrelationContext.NONE : context;
        String trace = blankToNull(traceId != null ? traceId : owner.traceId());
        String span = payload.spanId() != null ? payload.spanId() : spanId;
        long completed = payload.completedNanos() >= 0 ? payload.completedNanos() : completedNanos;
        AiPayload withSpan = Objects.equals(span, payload.spanId()) && completed == payload.completedNanos()
                ? payload
                : new AiPayload(
                        payload.operation(),
                        payload.provider(),
                        payload.model(),
                        payload.inputTokens(),
                        payload.outputTokens(),
                        payload.finishReason(),
                        payload.failed(),
                        span,
                        completed);
        remember(trace, withSpan.spanId(), withSpan.operation());
        return journal.offer(RuntimeEvent.of(
                JournalSource.AI,
                startEpochMillis,
                Math.max(0, durationNanos),
                owner,
                trace,
                thread,
                null,
                payload.failed(),
                withSpan));
    }

    /**
     * Whether the framework already reported the call a GenAI span describes: the same trace and span, or a call of the
     * same operation in the same trace whose span was unknown.
     */
    public static boolean recordedNatively(String traceId, String spanId, String operation) {
        if (traceId == null) {
            return false;
        }
        synchronized (NATIVE) {
            return (spanId != null && NATIVE.containsKey(traceId + ':' + spanId))
                    || (operation != null && NATIVE.containsKey(traceId + '|' + operation));
        }
    }

    /**
     * The journal's name for a framework's operation name: {@code chat} for {@code chat} and {@code text_completion},
     * {@code embeddings} for {@code embedding} and {@code embeddings}, and {@code null} for others, such as images.
     */
    public static String operation(String frameworkOperation) {
        if (frameworkOperation == null) {
            return null;
        }
        return switch (frameworkOperation.trim().toLowerCase(Locale.ROOT)) {
            case "chat", "text_completion" -> AiPayload.CHAT;
            case "embedding", "embeddings" -> AiPayload.EMBEDDINGS;
            default -> null;
        };
    }

    /** The first finish reason of a list the framework reports, such as {@code ["STOP"]}, lower-cased. */
    public static String finishReason(String reported) {
        if (reported == null) {
            return null;
        }
        String first = reported.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .split(",")[0]
                .trim();
        return first.isEmpty() ? null : first.toLowerCase(Locale.ROOT);
    }

    /** A token count a framework reports as text, or {@code null} when it reports none. */
    public static Long tokens(String reported) {
        if (reported == null || reported.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(reported.trim());
            return value < 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Forgets every remembered call; for tests. */
    static void forget() {
        synchronized (NATIVE) {
            NATIVE.clear();
        }
    }

    private static void remember(String traceId, String spanId, String operation) {
        if (traceId == null) {
            return;
        }
        synchronized (NATIVE) {
            NATIVE.put(spanId != null ? traceId + ':' + spanId : traceId + '|' + operation, Boolean.TRUE);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
