package io.github.jdubois.bootui.quarkus.correlation;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.smallrye.common.vertx.ContextLocals;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;

/**
 * Keeps a request's {@link CorrelationContext} on the Vert.x duplicated context that Quarkus creates for every HTTP
 * request ({@code docs/PLAN-v2.md} §5.1).
 *
 * <p>A Quarkus request starts on an event-loop thread and often continues on a worker or virtual thread. Quarkus
 * carries the request's duplicated context across those hops, so a value stored in its locals is visible wherever the
 * request's work runs, without instrumenting each dispatch. A thread-bound {@link BootUiCorrelation} scope, such as a
 * transaction opened inside the request, takes precedence over it.</p>
 *
 * <p>Values are only ever stored on a duplicated context, which belongs to one request. A root context is shared by
 * every request of its event loop, so nothing is stored there.</p>
 */
public final class QuarkusRequestCorrelation {

    static final String CONTEXT_KEY = "bootui.correlation";

    private QuarkusRequestCorrelation() {}

    /**
     * Attaches {@code context} to the current request's duplicated context.
     *
     * @return whether it was attached; {@code false} when the calling thread is not on a duplicated context
     */
    public static boolean attach(CorrelationContext context) {
        try {
            if (context == null || !VertxContext.isOnDuplicatedContext()) {
                return false;
            }
            ContextLocals.put(CONTEXT_KEY, context);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Attaches {@code correlation} to a given duplicated context, such as the one SmallRye Reactive Messaging processes
     * a message on, so every thread that continues that work reads it.
     *
     * @return whether it was attached; {@code false} when {@code context} is not a duplicated context
     */
    public static boolean attach(Context context, CorrelationContext correlation) {
        try {
            if (context == null || correlation == null || !VertxContext.isDuplicatedContext(context)) {
                return false;
            }
            context.putLocal(CONTEXT_KEY, correlation);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * The context of the work on this thread: the open thread scope when there is one, otherwise the context attached
     * to the current request's duplicated context, otherwise {@link CorrelationContext#NONE}.
     */
    public static CorrelationContext current() {
        if (BootUiCorrelation.cleared()) {
            // An explicitly cleared scope outranks the request's duplicated context: the work is correlated to nothing.
            return CorrelationContext.NONE;
        }
        CorrelationContext scoped = BootUiCorrelation.current();
        if (!scoped.isEmpty()) {
            return scoped;
        }
        try {
            if (!VertxContext.isOnDuplicatedContext()) {
                return CorrelationContext.NONE;
            }
            return ContextLocals.get(CONTEXT_KEY, CorrelationContext.NONE);
        } catch (RuntimeException ex) {
            return CorrelationContext.NONE;
        }
    }
}
