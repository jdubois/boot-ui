package io.github.jdubois.bootui.engine.codepaths;

/**
 * The recorded SQL, REST client, cache, and AI calls of a request, or of a route's warm requests, that no method node
 * holds, by why ({@code docs/PLAN-v2.md} §5.14, M5-4c). Immutable.
 *
 * @param otherThread calls recorded without a stamp: on a thread where the code-paths sensor had no fragment open, as
 *     another thread than the one that issued them (a WebClient response, a streaming AI call), or where the open calls
 *     held no node; a method that waited for them keeps their time in its own time
 * @param otherThreadNanos their time, unknown durations counted as 0
 * @param outside calls issued on the request's thread while its fragment was open but no instrumented method was, as
 *     in a filter, while the response was written, or in a transaction commit after the outermost instrumented method
 *     returned; their time is in no method's self time
 * @param missing stamped calls naming a fragment the tree did not hold when they were attached: dropped by the agent,
 *     past the fragments a tree tracks, or not arrived yet
 * @param late stamped calls whose fragment arrived after the tree settled: the fragment merged, the calls were not
 *     placed under its methods
 * @param overflow calls read past {@link RequestOutcome#MAX_CALLS} for one request, not placed
 */
public record UnplacedCalls(
        long otherThread, long otherThreadNanos, long outside, long missing, long late, long overflow) {

    /** No unplaced call. */
    public static final UnplacedCalls NONE = new UnplacedCalls(0L, 0L, 0L, 0L, 0L, 0L);

    public UnplacedCalls {
        otherThread = Math.max(0L, otherThread);
        otherThreadNanos = Math.max(0L, otherThreadNanos);
        outside = Math.max(0L, outside);
        missing = Math.max(0L, missing);
        late = Math.max(0L, late);
        overflow = Math.max(0L, overflow);
    }

    /** {@code calls} recorded without a stamp, as on another thread, of unknown time. */
    public static UnplacedCalls otherThread(long calls) {
        return calls <= 0 ? NONE : new UnplacedCalls(calls, 0L, 0L, 0L, 0L, 0L);
    }

    /** These calls and {@code other}'s. */
    public UnplacedCalls plus(UnplacedCalls other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new UnplacedCalls(
                otherThread + other.otherThread,
                otherThreadNanos + other.otherThreadNanos,
                outside + other.outside,
                missing + other.missing,
                late + other.late,
                overflow + other.overflow);
    }

    /** These calls with {@code count} more stamped calls whose fragment the tree does not hold. */
    UnplacedCalls withMissing(long count) {
        return count <= 0
                ? this
                : new UnplacedCalls(otherThread, otherThreadNanos, outside, missing + count, late, overflow);
    }

    /** These calls with {@code count} missing ones found to have arrived late. */
    UnplacedCalls arrivedLate(long count) {
        long moved = Math.min(Math.max(0L, count), missing);
        return moved == 0
                ? this
                : new UnplacedCalls(otherThread, otherThreadNanos, outside, missing - moved, late + moved, overflow);
    }

    /** The stamped calls placed under no node: missing, late, and past the bound. */
    public long stampedUnplaced() {
        return missing + late + overflow;
    }

    public boolean isEmpty() {
        return otherThread == 0 && outside == 0 && missing == 0 && late == 0 && overflow == 0;
    }
}
