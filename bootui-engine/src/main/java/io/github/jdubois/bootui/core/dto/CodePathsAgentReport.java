package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Code Paths for agents ({@code get_code_paths}, {@code bootui code paths}): the routes matching {@code query}, slowest
 * warm median first, at most {@code limit}, each with its top methods by self time; when exactly one route matches, its
 * nodes with the most self time too.
 *
 * @param available whether the code-paths sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param query the query as given, or {@code null}: empty for every route, else a route, or part of a route or method
 * @param matched how many routes matched
 * @param routes the matching routes, at most {@code limit}
 * @param omitted how many matching routes were left out past the limit
 * @param hottestNodes when exactly one route is listed, its method nodes with the most self time, at most {@code limit}
 * @param excludedMethods the methods adaptively excluded in this run
 * @param limitations what the trees cannot see
 */
public record CodePathsAgentReport(
        boolean available,
        String unavailableReason,
        String query,
        int matched,
        List<CodePathsRouteDto> routes,
        int omitted,
        List<CodePathsNodeDto> hottestNodes,
        List<String> excludedMethods,
        List<String> limitations) {

    /** The routes an agent gets when it asks for no limit. */
    public static final int DEFAULT_LIMIT = 10;

    public CodePathsAgentReport {
        routes = DtoCollections.immutableCopy(routes);
        hottestNodes = DtoCollections.immutableCopy(hottestNodes);
        excludedMethods = DtoCollections.immutableCopy(excludedMethods);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
