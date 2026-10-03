package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.AiCallEvents;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Records each model call Spring AI observes as {@code gen_ai.client.operation} in the runtime journal's {@code ai}
 * source ({@code docs/PLAN-v2.md} §5.18, M3-9), stamped with the request or execution that made it, so AI usage needs
 * no tracing. It reads only the observation's key values, never the prompt or completion, and names no Spring AI type,
 * so it costs nothing without Spring AI. When Micrometer Tracing is on, the call's span id lets the telemetry store skip
 * the GenAI span of the same call.
 */
public final class AiObservationJournalHandler implements ObservationHandler<Observation.Context> {

    /** Spring AI's observation of one model call, chat or embedding. */
    static final String OBSERVATION = "gen_ai.client.operation";

    private final Supplier<RuntimeJournal> journal;

    public AiObservationJournalHandler(Supplier<RuntimeJournal> journal) {
        this.journal = journal;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return OBSERVATION.equals(context.getName());
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(
                Started.class,
                new Started(
                        System.nanoTime(),
                        System.currentTimeMillis(),
                        BootUiCorrelation.current(),
                        Thread.currentThread().getName()));
    }

    @Override
    public void onStop(Observation.Context context) {
        try {
            record(context);
        } catch (RuntimeException | LinkageError ex) {
            // Recording never disturbs the call it observes.
        }
    }

    private void record(Observation.Context context) {
        Started started = context.get(Started.class);
        RuntimeJournal target = journal.get();
        String operation = AiCallEvents.operation(value(context, "gen_ai.operation.name"));
        if (started == null || target == null || operation == null) {
            return;
        }
        String responseModel = value(context, "gen_ai.response.model");
        boolean failed = context.getError() != null;
        String[] span = Tracing.span(context);
        long completed = System.nanoTime();
        AiCallEvents.publish(
                target,
                started.correlation(),
                span[0],
                span[1],
                started.epochMillis(),
                completed - started.nanos(),
                completed,
                started.thread(),
                new AiPayload(
                        operation,
                        value(context, "gen_ai.system"),
                        responseModel != null && !responseModel.isBlank()
                                ? responseModel
                                : value(context, "gen_ai.request.model"),
                        AiCallEvents.tokens(value(context, "gen_ai.usage.input_tokens")),
                        AiCallEvents.tokens(value(context, "gen_ai.usage.output_tokens")),
                        AiCallEvents.finishReason(value(context, "gen_ai.response.finish_reasons")),
                        failed));
    }

    private static String value(Observation.Context context, String key) {
        KeyValue value = context.getLowCardinalityKeyValue(key);
        if (value == null) {
            value = context.getHighCardinalityKeyValue(key);
        }
        if (value == null || value.getValue() == null || KeyValue.NONE_VALUE.equals(value.getValue())) {
            return null;
        }
        return value.getValue();
    }

    private record Started(long nanos, long epochMillis, CorrelationContext correlation, String thread) {}

    /** Reads Micrometer Tracing's span of the observation by name, so the handler loads without it. */
    static final class Tracing {

        private static final String TRACING_CONTEXT =
                "io.micrometer.tracing.handler.TracingObservationHandler$TracingContext";

        private Tracing() {}

        /** The trace id and span id of the observation's span, each {@code null} when unknown. */
        static String[] span(Observation.Context context) {
            try {
                Class<?> type =
                        Class.forName(TRACING_CONTEXT, false, context.getClass().getClassLoader());
                Object tracing = context.get(type);
                if (tracing == null) {
                    return new String[2];
                }
                Object span = method(tracing, "getSpan").invoke(tracing);
                if (span == null) {
                    return new String[2];
                }
                Object spanContext = method(span, "context").invoke(span);
                return new String[] {
                    (String) method(spanContext, "traceId").invoke(spanContext),
                    (String) method(spanContext, "spanId").invoke(spanContext)
                };
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                return new String[2];
            }
        }

        private static Method method(Object target, String name) throws NoSuchMethodException {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> contract : type.getInterfaces()) {
                    try {
                        return contract.getMethod(name);
                    } catch (NoSuchMethodException ex) {
                        // Try the next one.
                    }
                }
            }
            return target.getClass().getMethod(name);
        }
    }
}
