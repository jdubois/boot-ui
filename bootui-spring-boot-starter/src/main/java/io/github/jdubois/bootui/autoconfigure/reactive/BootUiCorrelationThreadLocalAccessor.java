package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
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
 * <p>Where it makes a request's context current on a thread and clears it again is also where that request's work on
 * a scheduler's pooled worker begins and ends: the BootUI agent's {@code thread-locals} sensor scans there ({@code
 * docs/PLAN-v2.md} §5.16, M5-5f), once per owner change, never on an event loop, which the bridge skips.
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

    /** The thread-locals scope this accessor opened on the thread, and the owner it opened it for. */
    private static final ThreadLocal<Scoped> SCOPED = new BootUiThreadLocal<>();

    private record Scoped(long token, String owner) {}

    @Override
    public void setValue(CorrelationContext value) {
        String owner = owner(value);
        Scoped scoped = SCOPED.get();
        if (scoped != null && !scoped.owner().equals(owner)) {
            closeScope(scoped);
            scoped = null;
        }
        BootUiCorrelation.replace(value);
        if (scoped == null && owner != null && AgentThreadLocals.bound()) {
            long token = AgentThreadLocals.open();
            if (token != 0L) {
                SCOPED.set(new Scoped(token, owner));
            }
        }
    }

    @Override
    public void setValue() {
        Scoped scoped = SCOPED.get();
        if (scoped != null) {
            closeScope(scoped);
        }
        BootUiCorrelation.replace(CorrelationContext.NONE);
    }

    private static void closeScope(Scoped scoped) {
        SCOPED.remove();
        AgentThreadLocals.close(scoped.token());
    }

    /** The request or execution {@code value} names, or {@code null} for none and for BootUI's own work. */
    private static String owner(CorrelationContext value) {
        if (value == null || value.isEmpty() || CorrelationContext.BOOTUI.equals(value)) {
            return null;
        }
        return Objects.toString(value.requestId(), "") + "|" + Objects.toString(value.executionId(), "");
    }
}
