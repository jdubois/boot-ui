package io.github.jdubois.bootui.engine.codepaths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The route trees of one run ({@code docs/PLAN-v2.md} §5.14, M5-4b): each settled request tree whose route the journal
 * named is merged into its route's {@link RouteTree}. JDK types only, current run only: a new claim generation starts a
 * new instance, and nothing is kept across runs. Bounded: at most {@value #MAX_ROUTES} routes, each with at most
 * {@value #MAX_NODES_PER_ROUTE} nodes, and {@value #MAX_NODES} nodes across routes; past the global budget, routes merge
 * further methods into their Other nodes while {@value #GLOBAL_OTHER_RESERVE} nodes remain for them. A tree of an
 * unknown route, or of a route past the cap, is counted and not merged. Not thread-safe.
 */
public final class RouteTrees {

    public static final int MAX_NODES_PER_ROUTE = 2_000;
    public static final int MAX_NODES = 100_000;
    public static final int GLOBAL_OTHER_RESERVE = 2_000;
    public static final int MAX_ROUTES = 500;

    /** The estimated bytes of one route-tree node: its arrays' entries, its histogram, and its index entry. */
    public static final int NODE_BYTES = 264;

    /** The estimated bytes of one route beyond its nodes: its durations and counters. */
    public static final int ROUTE_BYTES = 2_560;

    private final int maxNodesPerRoute;
    private final int maxNodes;
    private final int maxRoutes;
    private final Map<String, RouteTree> routes = new LinkedHashMap<>();
    private int nodes;
    private long merged;
    private long unrouted;
    private long routesDropped;
    private long version;

    public RouteTrees() {
        this(MAX_NODES_PER_ROUTE, MAX_NODES, MAX_ROUTES);
    }

    /**
     * Route trees bounded by {@code maxNodes} nodes and {@code maxRoutes} routes, at most {@value #MAX_NODES} and
     * {@value #MAX_ROUTES}, as a configured agent evidence bound scales them (M5-11).
     */
    public RouteTrees(int maxNodes, int maxRoutes) {
        this(
                MAX_NODES_PER_ROUTE,
                Math.max(100, Math.min(MAX_NODES, maxNodes)),
                Math.max(1, Math.min(MAX_ROUTES, maxRoutes)));
    }

    RouteTrees(int maxNodesPerRoute, int maxNodes, int maxRoutes) {
        this.maxNodesPerRoute = maxNodesPerRoute;
        this.maxNodes = maxNodes;
        this.maxRoutes = maxRoutes;
    }

    /**
     * Merges a settled request tree into the tree of {@code route}.
     *
     * @param route the route the journal named, or {@code null} or {@link RequestOutcome#UNKNOWN_ROUTE} when unknown
     * @param assemblyOnly whether the request's handler only assembled its result: it ran on an event loop, returned a
     *     reactive or asynchronous result, or BootUI could not tell where its work ran
     */
    public void add(RequestTree tree, String route, boolean assemblyOnly) {
        if (tree == null || tree.requestId() == null || route == null || RequestOutcome.UNKNOWN_ROUTE.equals(route)) {
            unrouted++;
            return;
        }
        RouteTree routeTree = routes.get(route);
        if (routeTree == null) {
            if (routes.size() >= maxRoutes || nodes >= maxNodes) {
                routesDropped++;
                return;
            }
            nodes++;
            routeTree = new RouteTree(route, this);
            routes.put(route, routeTree);
        }
        routeTree.offer(tree, assemblyOnly);
        merged++;
        version++;
    }

    /** One route's tree, or {@code null}. */
    public RouteTree route(String route) {
        return route == null ? null : routes.get(route);
    }

    /** Every route's tree, in first-seen order. */
    public List<RouteTree> routes() {
        return new ArrayList<>(routes.values());
    }

    /** The route trees' nodes, every route's request node included. */
    public int nodes() {
        return nodes;
    }

    /** Request trees merged, each route's first recorded request included. */
    public long merged() {
        return merged;
    }

    /** Request trees without a route the journal named. */
    public long unrouted() {
        return unrouted;
    }

    /** Request trees of a new route past the caps, not merged. */
    public long routesDropped() {
        return routesDropped;
    }

    /** Changes each time a tree is merged. */
    public long version() {
        return version;
    }

    /**
     * Empty route trees under the same bounds that keep {@code previous}'s counts since the claim, its version
     * included, as <b>Clear recording</b> does (M5-11).
     */
    public RouteTrees cleared() {
        RouteTrees next = new RouteTrees(maxNodesPerRoute, maxNodes, maxRoutes);
        next.merged = merged;
        next.unrouted = unrouted;
        next.routesDropped = routesDropped;
        next.version = version + 1;
        return next;
    }

    /** How many routes have a tree. */
    public int routeCount() {
        return routes.size();
    }

    /** The most nodes across routes. */
    public int maxNodes() {
        return maxNodes;
    }

    /** The most routes. */
    public int maxRoutes() {
        return maxRoutes;
    }

    /** The estimated bytes of every route tree. */
    public long estimatedBytes() {
        return (long) nodes * NODE_BYTES + (long) routes.size() * ROUTE_BYTES;
    }

    /** The estimated bytes of the most these route trees hold. */
    public long maxEstimatedBytes() {
        return (long) maxNodes * NODE_BYTES + (long) maxRoutes * ROUTE_BYTES;
    }

    int maxNodesPerRoute() {
        return maxNodesPerRoute;
    }

    /** Takes one node of the global budget for a regular node, if regular nodes remain. */
    boolean reserveRegular() {
        if (nodes >= maxNodes - Math.min(GLOBAL_OTHER_RESERVE, maxNodes / 10)) {
            return false;
        }
        nodes++;
        return true;
    }

    /** Takes one node of the global budget for an Other node, if any remain. */
    boolean reserveOther() {
        if (nodes >= maxNodes) {
            return false;
        }
        nodes++;
        return true;
    }
}
