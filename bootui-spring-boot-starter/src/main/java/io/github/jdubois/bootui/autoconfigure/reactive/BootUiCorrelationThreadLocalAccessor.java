package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * Exposes {@link BootUiCorrelation}'s holder to Micrometer context propagation under
 * {@link ReactiveRequestCorrelationFilter#CONTEXT_KEY}, so Reactor's automatic context propagation restores the
 * request's {@link CorrelationContext} on every thread a reactive request hops to, and clears it when the Reactor
 * context carries none. Loaded only when Micrometer context propagation is on the classpath.
 */
public final class BootUiCorrelationThreadLocalAccessor implements ThreadLocalAccessor<CorrelationContext> {

    /** Registers the accessor on the global registry, replacing any earlier registration under the same key. */
    public static void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new BootUiCorrelationThreadLocalAccessor());
    }

    @Override
    public Object key() {
        return ReactiveRequestCorrelationFilter.CONTEXT_KEY;
    }

    @Override
    public CorrelationContext getValue() {
        CorrelationContext current = BootUiCorrelation.current();
        return current.isEmpty() ? null : current;
    }

    @Override
    public void setValue(CorrelationContext value) {
        BootUiCorrelation.replace(value);
    }

    @Override
    public void setValue() {
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }
}
