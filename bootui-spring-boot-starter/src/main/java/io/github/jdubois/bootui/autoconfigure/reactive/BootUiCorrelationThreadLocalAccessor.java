package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.Objects;

/**
 * Exposes {@link BootUiCorrelation}'s holder to Micrometer context propagation under
 * {@link ReactiveRequestCorrelationFilter#CONTEXT_KEY}, so Reactor's automatic context propagation restores the
 * request's {@link CorrelationContext} on every thread a reactive request hops to, and clears it when the Reactor
 * context carries none. Loaded only when Micrometer context propagation is on the classpath.
 *
 * <p>Where it makes a request's context current on a scheduler's worker, it names the owner of the BootUI agent's
 * {@code thread-locals} scope {@link ReactorThreadLocalsScopes} opened around the worker's task ({@code
 * docs/PLAN-v2.md} §5.16, M5-5f).
 */
public final class BootUiCorrelationThreadLocalAccessor implements ThreadLocalAccessor<CorrelationContext> {

    /** Registers the accessor on the global registry, replacing any earlier registration under the same key. */
    public static void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new BootUiCorrelationThreadLocalAccessor());
    }

    /** Removes the accessor from the global registry, when the application context that registered it closes. */
    public static void unregister() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(ReactiveRequestCorrelationFilter.CONTEXT_KEY);
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
        if (owner(value) != null && AgentThreadLocals.bound()) {
            AgentThreadLocals.own();
        }
    }

    @Override
    public void setValue() {
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    /** The request or execution {@code value} names, or {@code null} for none and for BootUI's own work. */
    private static String owner(CorrelationContext value) {
        if (value == null || value.isEmpty() || CorrelationContext.BOOTUI.equals(value)) {
            return null;
        }
        return Objects.toString(value.requestId(), "") + "|" + Objects.toString(value.executionId(), "");
    }
}
