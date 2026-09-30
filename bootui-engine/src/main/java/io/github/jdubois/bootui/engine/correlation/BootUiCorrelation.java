package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;

/**
 * Holds the {@link CorrelationContext} of the work each thread is doing.
 *
 * <p>An adapter opens a {@link Scope} where its framework starts or resumes a request's work on a thread, and closes
 * it when that work leaves the thread. Closing always restores the context that was current before the scope
 * opened, so nested scopes, such as a transaction inside a request, unwind correctly, and a pooled thread never
 * carries one request's context into the next. When no scope is open, {@link #current()} returns
 * {@link CorrelationContext#NONE}.</p>
 *
 * <p>The holder is a plain {@link ThreadLocal}, which is also per virtual thread. It stores nothing for
 * {@link CorrelationContext#NONE}, so a thread with no open scope retains no value.</p>
 */
public final class BootUiCorrelation {

    private static final ThreadLocal<CorrelationContext> CURRENT = new ThreadLocal<>();

    private BootUiCorrelation() {}

    /** The context of the work on this thread; {@link CorrelationContext#NONE} when no scope is open. */
    public static CorrelationContext current() {
        CorrelationContext context = CURRENT.get();
        return context == null ? CorrelationContext.NONE : context;
    }

    /**
     * Makes {@code context} current on this thread until the returned scope is closed, which restores the previous
     * context. Use it with try-with-resources.
     *
     * @param context the context to make current; {@code null} is treated as {@link CorrelationContext#NONE}
     */
    public static Scope open(CorrelationContext context) {
        return new Scope(replace(context));
    }

    /**
     * Makes {@code context} current on this thread and returns the context it replaces, for bridges that manage
     * restoration themselves, such as a Reactor {@code ThreadLocalAccessor}. Prefer {@link #open}.
     *
     * @param context the context to make current; {@code null} is treated as {@link CorrelationContext#NONE}
     * @return the context that was current, never {@code null}
     */
    public static CorrelationContext replace(CorrelationContext context) {
        CorrelationContext previous = current();
        if (context == null || context.isEmpty()) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return previous;
    }

    /**
     * An open correlation scope. Closing it restores the context that was current when it opened; closing it again has
     * no effect.
     */
    public static final class Scope implements AutoCloseable {

        private final CorrelationContext previous;
        private boolean closed;

        private Scope(CorrelationContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                replace(previous);
            }
        }
    }
}
