package io.github.jdubois.bootui.core.dto;

/**
 * The code-paths sensor's engine counters for this run ({@code docs/PLAN-v2.md} §5.14).
 *
 * @param generation this run's claim generation
 * @param fragments fragments the agent flushed and this run merged
 * @param staleFragments fragments of another claim generation, dropped
 * @param malformedFragments fragments that could not be read, dropped
 * @param settledTrees request trees settled
 * @param routedTrees settled trees merged into a route tree, each route's first recorded request included
 * @param unroutedTrees settled trees whose route the journal no longer named, not in a route tree
 * @param routes routes with a tree
 * @param routeNodes nodes across every route tree
 * @param routeNodeBudget the most nodes across route trees
 * @param foldedCalls calls whose time stayed in their parent because a route tree had no node left for them
 */
public record CodePathsStatusDto(
        long generation,
        long fragments,
        long staleFragments,
        long malformedFragments,
        long settledTrees,
        long routedTrees,
        long unroutedTrees,
        int routes,
        int routeNodes,
        int routeNodeBudget,
        long foldedCalls) {}
