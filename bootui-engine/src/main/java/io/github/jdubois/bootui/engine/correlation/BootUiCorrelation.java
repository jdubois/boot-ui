package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
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
 * {@link CorrelationContext#NONE}, so a thread with no open scope retains no value. A scope opened by
 * {@link #openCleared()} is the one exception: it stores a marker, so an adapter that falls back to an ambient context
 * when no scope is open, as Quarkus does with the request's Vert.x duplicated context, can tell a thread that was
 * deliberately cleared from one that simply has no scope ({@code docs/PLAN-v2.md} D30).</p>
 *
 * <p>Every change of context also tells the {@link SegmentMeter} which request the thread now works for, so a
 * request's CPU time, allocated bytes, and GC pauses are measured over exactly the segments its scopes cover
 * ({@code docs/PLAN-v2.md} §5.11).</p>
 */
public final class BootUiCorrelation {

    private static final ThreadLocal<CorrelationContext> CURRENT = new BootUiThreadLocal<>();

    /**
     * Held while an {@link #openCleared()} scope is open. It is a distinct instance, compared by identity and never
     * handed out, so no context a caller supplies can be mistaken for it even though it equals
     * {@link CorrelationContext#NONE}.
     */
    private static final CorrelationContext CLEARED =
            new CorrelationContext(null, null, null, null, null, null, null, null, null, false);

    private BootUiCorrelation() {}

    /** The context of the work on this thread; {@link CorrelationContext#NONE} when no scope is open. */
    public static CorrelationContext current() {
        CorrelationContext context = CURRENT.get();
        return context == null || context == CLEARED ? CorrelationContext.NONE : context;
    }

    /**
     * Whether a scope on this thread explicitly cleared the context, which is not the same as having no scope: an
     * adapter that otherwise falls back to an ambient context must honour the clearing and record nothing under the
     * ambient request.
     */
    public static boolean cleared() {
        return CURRENT.get() == CLEARED;
    }

    /**
     * Makes {@code context} current on this thread until the returned scope is closed, which restores the previous
     * context. Use it with try-with-resources.
     *
     * @param context the context to make current; {@code null} is treated as {@link CorrelationContext#NONE}
     */
    public static Scope open(CorrelationContext context) {
        CorrelationContext previous = CURRENT.get();
        String metered = SegmentMeter.shared().currentRequestId();
        replace(context);
        return new Scope(previous, metered);
    }

    /**
     * Clears the context on this thread until the returned scope is closed, so work that follows is correlated to no
     * request even on an adapter that would otherwise fall back to an ambient context, and stops metering it for any
     * request. Closing restores exactly what was current when the scope opened, including the request the thread was
     * metered for, so a pooled thread carries no residue and a surrounding scope is left intact.
     */
    public static Scope openCleared() {
        CorrelationContext previous = CURRENT.get();
        String metered = SegmentMeter.shared().currentRequestId();
        CURRENT.set(CLEARED);
        SegmentMeter.shared().switchTo(null);
        return new Scope(previous, metered);
    }

    /**
     * Makes {@code context}, a request's context propagated to another thread by the BootUI agent, current on this
     * thread until the returned scope is closed, <em>without</em> changing which request the thread is metered for
     * ({@code docs/PLAN-v2.md} D32): work handed to an executor is not part of its request's own CPU time and
     * allocation. Closing restores the previous context and switches the meter back to the request the thread was
     * metered for when the scope opened, so a metered scope nested inside cannot leave the thread charged to another
     * request.
     *
     * @param context the context to make current; {@code null} is treated as {@link CorrelationContext#NONE}
     */
    public static Scope openPropagated(CorrelationContext context) {
        CorrelationContext previous = CURRENT.get();
        String metered = SegmentMeter.shared().currentRequestId();
        set(context);
        return new Scope(previous, metered);
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
            SegmentMeter.shared().switchTo(null);
        } else {
            CURRENT.set(context);
            SegmentMeter.shared().switchTo(context.requestId());
        }
        return previous;
    }

    /** Makes {@code context} current without telling the meter. */
    private static void set(CorrelationContext context) {
        if (context == null || context.isEmpty()) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /**
     * Puts back exactly what the holder held, including the cleared marker, without telling the meter. Unlike
     * {@link #set} this never normalizes, so closing a scope restores the state its opening saw rather than an
     * equivalent-looking one.
     */
    private static void restore(CorrelationContext raw) {
        if (raw == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(raw);
        }
    }

    /**
     * An open correlation scope. Closing it restores the context that was current when it opened, and the request the
     * thread was metered for; closing it again has no effect.
     *
     * <p>The metered request is captured separately from the context because the two can legitimately differ: Quarkus
     * meters a request whose correlation lives in its Vert.x duplicated context rather than in this holder, so
     * deriving the meter from the restored context would silently stop measuring that request.</p>
     */
    public static final class Scope implements AutoCloseable {

        private final CorrelationContext previous;
        private final String metered;
        private boolean closed;

        private Scope(CorrelationContext previous, String metered) {
            this.previous = previous;
            this.metered = metered;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                restore(previous);
                SegmentMeter.shared().switchTo(metered);
            }
        }
    }
}
