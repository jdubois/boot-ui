package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import java.util.function.Supplier;

/**
 * The default {@link CorrelationContextProvider}: the context of the current {@link BootUiCorrelation} scope, with its
 * trace id filled in from the adapter's {@link TraceIdSource} when the scope has none.
 *
 * <p>Adapters open scopes as requests start, when the trace id may not exist yet, and the tracer knows the trace of the
 * work being recorded. Filling in the trace id at read time keeps both exact without making every adapter update its
 * scopes as spans start and end.</p>
 */
public final class ScopedCorrelationContextProvider implements CorrelationContextProvider {

    private final Supplier<CorrelationContext> scope;
    private final TraceIdSource traceIds;

    /**
     * @param traceIds the adapter's tracer bridge, or {@code null} when the adapter has none
     */
    public ScopedCorrelationContextProvider(TraceIdSource traceIds) {
        this(BootUiCorrelation::current, traceIds);
    }

    /**
     * @param scope where the adapter keeps the context of the current work, for adapters that also keep it outside
     *     the thread, such as on a Vert.x context; it must never return {@code null}
     * @param traceIds the adapter's tracer bridge, or {@code null} when the adapter has none
     */
    public ScopedCorrelationContextProvider(Supplier<CorrelationContext> scope, TraceIdSource traceIds) {
        this.scope = scope;
        this.traceIds = traceIds;
    }

    @Override
    public CorrelationContext current() {
        CorrelationContext context = currentScope();
        if (context.traceId() != null || traceIds == null) {
            return context;
        }
        String traceId = traceId();
        return traceId == null ? context : context.withTrace(traceId, context.spanId());
    }

    private CorrelationContext currentScope() {
        try {
            CorrelationContext context = scope.get();
            return context == null ? CorrelationContext.NONE : context;
        } catch (RuntimeException | LinkageError ex) {
            return BootUiCorrelation.current();
        }
    }

    private String traceId() {
        try {
            String traceId = traceIds.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
