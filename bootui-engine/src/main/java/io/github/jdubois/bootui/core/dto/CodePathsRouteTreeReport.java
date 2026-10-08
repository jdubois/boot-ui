package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One route's call tree in this run ({@code docs/PLAN-v2.md} §5.14), paged by depth: the nodes at most {@code depth}
 * levels below the request, depth first with each parent's children slowest first, then {@code offset} and
 * {@code limit}.
 *
 * @param available whether the code-paths sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param route the route asked for
 * @param found whether this run has a tree for it
 * @param assemblyOnly whether its handler only assembled its result: it ran on an event loop, returned a reactive or
 *     asynchronous result, or BootUI could not tell where its work ran
 * @param warmRequests the warm requests merged
 * @param firstRequestMillis the route's first recorded request's own time, the first whose tree settled, kept apart
 *     from the warm requests, or {@code null}
 * @param firstRequestId that request's id, or {@code null}
 * @param ownMillis the warm requests' mean own time
 * @param handlerMillis the mean time of the handler's application methods, or {@code null} when no handler phase is known
 * @param shareOf what each node's share is of: {@code handler} or {@code request}
 * @param depth the deepest level returned
 * @param nodes the page of nodes
 * @param methods where each method on the page is called from
 * @param exemplarRequestIds request ids whose trees are kept: the slowest, then the latest failed
 * @param page paging metadata
 * @param limitations what the tree cannot see
 */
public record CodePathsRouteTreeReport(
        boolean available,
        String unavailableReason,
        String route,
        boolean found,
        boolean assemblyOnly,
        long warmRequests,
        Double firstRequestMillis,
        String firstRequestId,
        double ownMillis,
        Double handlerMillis,
        String shareOf,
        int depth,
        List<CodePathsNodeDto> nodes,
        List<CodePathsMethodDto> methods,
        List<String> exemplarRequestIds,
        PageMetadata page,
        List<String> limitations) {

    public CodePathsRouteTreeReport {
        nodes = DtoCollections.immutableCopy(nodes);
        methods = DtoCollections.immutableCopy(methods);
        exemplarRequestIds = DtoCollections.immutableCopy(exemplarRequestIds);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** The report without the sensor, or for a route this run has no tree for. */
    public static CodePathsRouteTreeReport empty(boolean available, String reason, String route, int depth, int limit) {
        return new CodePathsRouteTreeReport(
                available,
                reason,
                route,
                false,
                false,
                0L,
                null,
                null,
                0.0,
                null,
                "handler",
                depth,
                List.of(),
                List.of(),
                List.of(),
                new PageMetadata(0, 0, 0, limit, 0, false),
                List.of());
    }
}
