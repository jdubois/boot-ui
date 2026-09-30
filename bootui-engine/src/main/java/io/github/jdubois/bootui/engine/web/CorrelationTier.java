package io.github.jdubois.bootui.engine.web;

/**
 * How a child signal was tied to the execution a profile anchors on, strongest first.
 *
 * <p>{@link #REQUEST_ID} is exact by construction. {@link #TRACE_ID} and {@link #SERVING_THREAD} are exact under the
 * unique-candidate rule; {@link #TIME_WINDOW} is a labelled heuristic, so a profile that used it is marked
 * approximate.</p>
 */
public enum CorrelationTier {

    /**
     * The child carries the BootUI request id the anchor carries ({@code docs/PLAN-v2.md} §5.1), stamped when the
     * child was recorded, with or without tracing.
     */
    REQUEST_ID,

    /** The child carries a distributed-trace id that exactly one anchor of any type carries. */
    TRACE_ID,

    /** The child ran on the one thread that served the anchor, inside the anchor's window. */
    SERVING_THREAD,

    /** The child falls inside the anchor's time window, and inside no other anchor's window. */
    TIME_WINDOW;

    /** Whether this tier is weaker than {@code other}. */
    boolean weakerThan(CorrelationTier other) {
        return other == null || ordinal() > other.ordinal();
    }
}
