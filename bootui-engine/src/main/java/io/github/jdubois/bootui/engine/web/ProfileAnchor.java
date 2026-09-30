package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;

/**
 * One execution that correlated children can attach to, reduced to the evidence every correlation tier
 * reads. Only {@link Type#REQUEST} anchors exist today; a later anchor type plugs in by supplying its own
 * window, trace id, and serving thread (docs/PLAN.md §3.20).
 *
 * @param type the kind of execution
 * @param id the Live Activity entry id of the anchor
 * @param traceId the distributed-trace id the execution carried, or {@code null}
 * @param startMillis the recorded start of the execution, in epoch milliseconds
 * @param endMillis the recorded end of the execution, in epoch milliseconds
 * @param traceWindowBounded whether a trace-id match must also fall inside the anchor's exact window; an
 *     HTTP request's trace id claims work it caused even after the response completed, so a request's trace
 *     window is open
 * @param servingThread the one thread that served the execution, or {@code null} when it is unknown or
 *     the adapter has no thread-per-execution model
 * @param servingStartMillis the start of the serving-thread window
 * @param servingEndMillis the end of the serving-thread window
 * @param timeWindowTier whether the heuristic time-window tier may attach children to this anchor
 * @param method the HTTP method, or {@code null} for a non-HTTP anchor
 * @param path the HTTP path, or {@code null} for a non-HTTP anchor
 * @param principal the authenticated principal, or {@code null}
 */
record ProfileAnchor(
        Type type,
        String id,
        String traceId,
        long startMillis,
        long endMillis,
        boolean traceWindowBounded,
        String servingThread,
        long servingStartMillis,
        long servingEndMillis,
        boolean timeWindowTier,
        String method,
        String path,
        String principal) {

    /**
     * Slack, in milliseconds, applied on both ends of a window when attributing a child to it, to absorb
     * clock granularity between capture sources.
     */
    static final long WINDOW_SLACK_MS = 50L;

    /** The kinds of execution a profile can anchor on. */
    enum Type {
        REQUEST
    }

    /**
     * An HTTP request anchor, refined by the serving thread the adapter resolved for it, if any.
     *
     * @param timeWindowTier whether the adapter can use the request's time window as a last-resort tier
     */
    static ProfileAnchor request(
            HttpExchangeDto exchange, ProfileCapabilities.ServingThread served, boolean timeWindowTier) {
        long start = exchange.timestamp() == null ? 0L : exchange.timestamp().toEpochMilli();
        long end = exchange.durationMs() == null ? start : start + exchange.durationMs();
        String thread = served == null ? null : BlankStrings.blankToNull(served.thread());
        return new ProfileAnchor(
                Type.REQUEST,
                exchange.id(),
                BlankStrings.blankToNull(exchange.traceId()),
                start,
                end,
                false,
                thread,
                thread == null ? start : served.startMillis(),
                thread == null ? end : served.endMillis(),
                timeWindowTier,
                exchange.method(),
                exchange.path(),
                exchange.principal());
    }

    /** Whether {@code timestamp} falls inside the anchor's window, widened by the slack on both ends. */
    boolean covers(long timestamp) {
        return timestamp >= startMillis - WINDOW_SLACK_MS && timestamp <= endMillis + WINDOW_SLACK_MS;
    }

    /** Whether {@code timestamp} falls inside the anchor's exact window, without slack. */
    boolean contains(long timestamp) {
        return timestamp >= startMillis && timestamp <= endMillis;
    }

    /** Whether a child carrying this anchor's trace id at {@code timestamp} may attach by trace id. */
    boolean traceWindowAdmits(long timestamp) {
        return !traceWindowBounded || contains(timestamp);
    }

    /** Whether a heuristic tier — the serving thread or the time window — can attach children to this anchor. */
    boolean hasHeuristicTier() {
        return servingThread != null || timeWindowTier;
    }

    /**
     * Whether a security event for {@code eventPrincipal} could have come from this anchor by its time
     * window. Only an HTTP request has a principal to compare: it admits an event whose principal is
     * unknown or equal to its own. An anchor without a principal admits no security event by window.
     */
    boolean admitsPrincipal(String eventPrincipal) {
        if (type != Type.REQUEST) {
            return false;
        }
        return principal == null || eventPrincipal == null || principal.equalsIgnoreCase(eventPrincipal);
    }

    /**
     * Whether a signal recorded with this request context could have come from this anchor. An HTTP anchor
     * requires the same path and, when both are known, the same method; any other anchor admits only a
     * signal recorded outside an HTTP request.
     */
    boolean admitsRequestContext(String requestMethod, String requestPath) {
        if (type != Type.REQUEST) {
            return requestPath == null;
        }
        if (path == null || requestPath == null || !path.equalsIgnoreCase(requestPath)) {
            return false;
        }
        return method == null || requestMethod == null || method.equalsIgnoreCase(requestMethod);
    }

    /** Whether a child recorded on {@code thread} at {@code timestamp} ran on this anchor's serving thread. */
    boolean servedOn(String thread, long timestamp) {
        return servingThread != null
                && servingThread.equals(thread)
                && timestamp >= servingStartMillis - WINDOW_SLACK_MS
                && timestamp <= servingEndMillis + WINDOW_SLACK_MS;
    }
}
