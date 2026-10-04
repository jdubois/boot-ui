package io.github.jdubois.bootui.engine.codepaths;

/**
 * What the runtime journal says about a request whose call tree was kept ({@code docs/PLAN-v2.md} §5.14): its route and
 * whether it failed, so the store keeps slow and failed exemplars per route.
 *
 * @param route the route label, such as {@code GET /api/orders/{id}}
 * @param status the response status, 0 when unknown
 * @param failed whether the request failed: a 5xx status or a failed exchange
 */
public record RequestOutcome(String route, int status, boolean failed) {

    /** The route of a request the journal does not name, or no longer retains. */
    public static final String UNKNOWN_ROUTE = "(unknown route)";

    /** A request the journal says nothing about. */
    public static final RequestOutcome UNKNOWN = new RequestOutcome(UNKNOWN_ROUTE, 0, false);
}
