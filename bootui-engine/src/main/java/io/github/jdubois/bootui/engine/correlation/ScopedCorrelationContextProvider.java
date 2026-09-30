package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.TraceIdProvider;

/**
 * The default {@link CorrelationContextProvider}: the context of the current {@link BootUiCorrelation} scope, with its
 * trace id filled in from the adapter's {@link TraceIdProvider} when the scope has none.
 *
 * <p>Adapters open scopes as requests start, when the trace id may not exist yet, and the tracer knows the trace of the
 * work being recorded. Filling in the trace id at read time keeps both exact without making every adapter update its
 * scopes as spans start and end.</p>
 */
public final class ScopedCorrelationContextProvider implements CorrelationContextProvider {

    private final TraceIdProvider traceIdProvider;

    /**
     * @param traceIdProvider the adapter's tracer bridge, or {@code null} when the adapter has none
     */
    public ScopedCorrelationContextProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    public CorrelationContext current() {
        CorrelationContext context = BootUiCorrelation.current();
        if (context.traceId() != null || traceIdProvider == null) {
            return context;
        }
        String traceId = traceId();
        return traceId == null ? context : context.withTrace(traceId, context.spanId());
    }

    private String traceId() {
        try {
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
