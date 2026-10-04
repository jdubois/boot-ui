package io.github.jdubois.bootui.core.dto;

/**
 * One node of a route or request call tree ({@code docs/PLAN-v2.md} §5.14). In a route tree, calls and times are per
 * warm request of the route, and percentiles are over the requests that reached the node; in a request tree, they are
 * the request's.
 *
 * @param id the node's index in its tree, 0 for the request
 * @param parent its parent's index, or {@code null} for the request
 * @param depth its depth below the request
 * @param kind {@code REQUEST}, {@code METHOD}, {@code ASYNC} (work an executor ran for the request, shown apart), or
 *     {@code OTHER} (a parent's methods past the tree's node budget)
 * @param method the method's key, {@code class#name+descriptor}, for a {@code METHOD} node
 * @param className its class, for a {@code METHOD} node
 * @param methodName its name, for a {@code METHOD} node
 * @param phase the request phase it was entered in, {@code FILTERS}, {@code HANDLER}, or {@code RESPONSE}, or
 *     {@code null} when unknown
 * @param async whether it is asynchronous work or under it
 * @param requests the requests that reached it
 * @param callsPerRequest its calls per request
 * @param totalMillis its time per request, its children's included
 * @param selfMillis its time per request outside its recorded children
 * @param share its time's share of the handler's time in application methods, or of the request's own time when no
 *     handler phase is known, in percent; {@code null} for asynchronous work and nodes outside that phase
 * @param p50Millis the median time per request that reached it, approximate (≈): interpolated within a log2
 *     histogram bucket and clamped to the node's least and most time per request; {@code null} in a request tree
 * @param p95Millis its 95th percentile, approximate in the same way, or {@code null}
 * @param children its child nodes
 */
public record CodePathsNodeDto(
        int id,
        Integer parent,
        int depth,
        String kind,
        String method,
        String className,
        String methodName,
        String phase,
        boolean async,
        long requests,
        double callsPerRequest,
        double totalMillis,
        double selfMillis,
        Double share,
        Double p50Millis,
        Double p95Millis,
        int children) {}
