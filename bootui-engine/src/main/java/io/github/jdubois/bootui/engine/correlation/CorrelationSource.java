package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;

/**
 * The replaceable source a recorder reads the {@link CorrelationContext} of the work it records from
 * ({@code docs/PLAN-v2.md} §5.1). It defaults to the thread's {@link BootUiCorrelation} scope, which Spring MVC opens
 * on the serving thread and Spring WebFlux restores on every Reactor hop. The Quarkus adapter installs a provider that
 * also reads the request's Vert.x context, because blocking work continues on a worker thread.
 *
 * <p>Every read is fully guarded, so a failing provider never disrupts the recorded work.</p>
 */
public final class CorrelationSource {

    private static final CorrelationContextProvider DEFAULT = BootUiCorrelation::current;

    private volatile CorrelationContextProvider provider = DEFAULT;

    /** Replaces the provider; {@code null} restores the thread scope. */
    public void set(CorrelationContextProvider provider) {
        this.provider = provider == null ? DEFAULT : provider;
    }

    /** The current context, or {@link CorrelationContext#NONE} when nothing owns the work or the provider fails. */
    public CorrelationContext current() {
        try {
            CorrelationContext context = provider.current();
            return context == null ? CorrelationContext.NONE : context;
        } catch (RuntimeException ex) {
            return CorrelationContext.NONE;
        }
    }

    /** The current request id, or {@code null} when no request owns the work. */
    public String requestId() {
        return current().requestId();
    }
}
