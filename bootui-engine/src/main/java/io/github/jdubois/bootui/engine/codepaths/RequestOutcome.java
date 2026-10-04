package io.github.jdubois.bootui.engine.codepaths;

import java.util.List;

/**
 * What the runtime journal says about a request whose call tree was kept ({@code docs/PLAN-v2.md} §5.14): its route and
 * whether it failed, so the store keeps slow and failed exemplars per route, and the SQL, REST client, cache, and AI calls
 * it recorded with a code-paths stamp (M5-4c), which the tree attaches under the node that issued them.
 *
 * @param route the route label, such as {@code GET /api/orders/{id}}
 * @param status the response status, 0 when unknown
 * @param failed whether the request failed: a 5xx status or a failed exchange
 * @param calls its stamped calls, at most {@link #MAX_CALLS}
 * @param unplaced its recorded calls no stamp places: without a stamp, issued outside every instrumented method, or
 *     stamped past {@link #MAX_CALLS} ({@link UnplacedCalls})
 */
public record RequestOutcome(
        String route, int status, boolean failed, List<StampedCall> calls, UnplacedCalls unplaced) {

    /** The route of a request the journal does not name, or no longer retains. */
    public static final String UNKNOWN_ROUTE = "(unknown route)";

    /** A request the journal says nothing about. */
    public static final RequestOutcome UNKNOWN = new RequestOutcome(UNKNOWN_ROUTE, 0, false);

    /** The most stamped calls kept for one request; later ones count as {@link UnplacedCalls#overflow()}. */
    public static final int MAX_CALLS = 10_000;

    public RequestOutcome {
        calls = calls == null ? List.of() : List.copyOf(calls);
        unplaced = unplaced == null ? UnplacedCalls.NONE : unplaced;
    }

    /** A request without recorded calls. */
    public RequestOutcome(String route, int status, boolean failed) {
        this(route, status, failed, List.of(), UnplacedCalls.NONE);
    }

    /** A request with {@code calls} and {@code unstampedCalls} recorded without a stamp, of unknown time. */
    public RequestOutcome(String route, int status, boolean failed, List<StampedCall> calls, long unstampedCalls) {
        this(route, status, failed, calls, UnplacedCalls.otherThread(unstampedCalls));
    }

    /** Its recorded calls without a stamp ({@link UnplacedCalls#otherThread()}). */
    public long unstampedCalls() {
        return unplaced.otherThread();
    }

    /** This outcome without its calls, as a kept tree remembers it once they are attached. */
    public RequestOutcome withoutCalls() {
        return calls.isEmpty() && unplaced.isEmpty() ? this : new RequestOutcome(route, status, failed);
    }

    /**
     * One recorded call with its code-paths stamp.
     *
     * @param kind {@link CodePathStamps#SQL}, {@link CodePathStamps#REST}, {@link CodePathStamps#CACHE}, or
     *     {@link CodePathStamps#AI}
     * @param stamp its stamp, never 0
     * @param durationNanos its duration, or -1 when unknown, as a cache access's
     */
    public record StampedCall(int kind, long stamp, long durationNanos) {}
}
