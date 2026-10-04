package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One route's call tree in this run, summarized ({@code docs/PLAN-v2.md} §5.14). Times are per warm request, in the
 * request's own recorded time: its threads' fragments, asynchronous children apart.
 *
 * @param route the route label, such as {@code GET /api/orders/{id}}
 * @param warmRequests the warm requests merged
 * @param firstRequestMillis the route's first recorded request's own time, the first whose tree settled, kept apart
 *     from the warm requests, or {@code null}
 * @param p50Millis the warm requests' median own time
 * @param p95Millis their 95th percentile
 * @param meanMillis their mean
 * @param asyncMillis their asynchronous children's mean time, shown apart
 * @param assemblyOnly whether its handler only assembled its result: it ran on an event loop, returned a reactive or
 *     asynchronous result, or BootUI could not tell where its work ran; the tree times the assembly, not the work, and the route is kept out of {@code route-time-breakdown}'s handler split
 * @param nodes the tree's nodes
 * @param topMethods its methods with the most self time, at most five
 */
public record CodePathsRouteDto(
        String route,
        long warmRequests,
        Double firstRequestMillis,
        double p50Millis,
        double p95Millis,
        double meanMillis,
        double asyncMillis,
        boolean assemblyOnly,
        int nodes,
        List<CodePathsMethodTimeDto> topMethods) {

    public CodePathsRouteDto {
        topMethods = DtoCollections.immutableCopy(topMethods);
    }
}
