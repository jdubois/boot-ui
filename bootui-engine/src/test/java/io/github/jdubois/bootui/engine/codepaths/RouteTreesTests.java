package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Route trees ({@code docs/PLAN-v2.md} §5.14, M5-4b): examples of the first recorded request, assembly only, and the
 * histogram, then seeded random request trees against a straightforward path-keyed reference of the merge, and, under
 * small budgets, the invariants the merge keeps: node caps, one Other node per parent and phase, and self time
 * conserved.
 */
class RouteTreesTests {

    private static final String ROUTE = "GET /api/orders";

    /**
     * The route's first recorded request, the first whose tree settled, is kept apart as its start, length, and id
     * only; a later tree that started earlier is merged as warm, since trees settle in about the order requests ended.
     */
    @Test
    void theFirstRecordedRequestIsKeptApartAsItsStartLengthAndIdOnly() {
        RouteTrees trees = new RouteTrees();
        RequestTree second = new Tree(2_000L).method(0, 7, 2, 1, 40_000_000L).build("0000000000000002");
        RequestTree first = new Tree(1_000L).method(0, 7, 2, 1, 90_000_000L).build("0000000000000001");
        RequestTree third = new Tree(3_000L).method(0, 7, 2, 1, 50_000_000L).build("0000000000000003");

        trees.add(second, ROUTE, false);
        RouteTree route = trees.route(ROUTE);
        assertThat(route.warmRequests()).isZero();
        assertThat(route.hasFirstRequest()).isTrue();
        assertThat(route.firstRequestId()).isEqualTo("0000000000000002");
        assertThat(route.firstRequestStartNanos()).isEqualTo(2_000L);
        assertThat(route.firstRequestNanos()).isEqualTo(second.durationNanos());

        trees.add(first, ROUTE, false);
        assertThat(route.firstRequestId()).as("the first recorded stays apart").isEqualTo("0000000000000002");
        assertThat(route.warmRequests()).isEqualTo(1);
        trees.add(third, ROUTE, false);

        assertThat(route.warmRequests()).isEqualTo(2);
        assertThat(route.totalNanos(1)).isEqualTo(140_000_000L);
        assertThat(route.requests(1)).isEqualTo(2);
        assertThat(route.recentDurations()).containsExactlyInAnyOrder(first.durationNanos(), third.durationNanos());
        assertThat(trees.merged()).isEqualTo(3);
    }

    /**
     * M5-7a: each route counts the requests that executed each method, the first recorded included, at any depth and
     * in executor work, read from the request tree before the route tree folds anything; a tree with an Other node is
     * incomplete; a tree without a route keeps its methods apart.
     */
    @Test
    void eachRouteCountsTheRequestsThatExecutedEachMethodTheFirstIncluded() {
        RouteTrees trees = new RouteTrees();
        Tree first = new Tree(0L);
        int outer = first.add(0, 7, 2, 1, 1_000L);
        first.add(outer, 8, 2, 3, 500L);
        trees.add(first.build("0000000000000001"), ROUTE, false);
        Tree second = new Tree(0L);
        int async = second.add(0, RequestTree.ASYNC, 0, 1, 0L);
        second.add(async, 9, 0, 1, 100L);
        second.add(0, 7, 2, 2, 100L);
        second.add(0, RequestTree.OTHER, 2, 1, 100L);
        trees.add(second.build("0000000000000002"), ROUTE, false);
        trees.add(new Tree(0L).method(0, 10, 2, 1, 1L).build("0000000000000003"), RequestOutcome.UNKNOWN_ROUTE, false);

        RouteTree route = trees.route(ROUTE);
        assertThat(route.requestsSeen()).isEqualTo(2);
        assertThat(route.executedRequests(7))
                .as("each request once, the first included")
                .isEqualTo(2);
        assertThat(route.executedRequests(8))
                .as("at any depth, in the first request")
                .isEqualTo(1);
        assertThat(route.executedRequests(9)).as("in executor work").isEqualTo(1);
        assertThat(route.executedRequests(10)).isZero();
        assertThat(route.executedMethods()).containsExactlyInAnyOrder(7, 8, 9);
        assertThat(route.incompleteRequests()).as("the tree with an Other node").isEqualTo(1);
        assertThat(trees.unroutedExecutions(10)).isEqualTo(1);

        trees.amend(ROUTE, new int[] {RequestTree.REQUEST, 7}, new int[] {7, 11}, false);
        assertThat(route.executedRequests(7))
                .as("the request already counted it")
                .isEqualTo(2);
        assertThat(route.executedRequests(11)).isEqualTo(1);
        assertThat(route.amendments()).isEqualTo(1);
        assertThat(route.amendedWithoutTree()).isFalse();
        trees.amend(ROUTE, null, new int[] {7, 12}, true);
        assertThat(route.executedRequests(7))
                .as("without the tree, a known method is not counted again")
                .isEqualTo(2);
        assertThat(route.executedRequests(12)).isEqualTo(1);
        assertThat(route.amendedWithoutTree()).isTrue();
        assertThat(route.incompleteRequests()).isEqualTo(2);
        trees.amend("GET /unknown", new int[0], new int[] {13}, false);
        assertThat(trees.unroutedExecutions(13)).isEqualTo(1);
    }

    @Test
    void aRoutesMethodTableIsBounded() {
        RouteTrees trees = new RouteTrees();
        int half = RouteTrees.MAX_METHODS_PER_ROUTE / 2 + 1;
        for (int request = 0; request < 2; request++) {
            Tree wide = new Tree(0L);
            for (int id = request * half; id < (request + 1) * half; id++) {
                wide.add(0, id, 2, 1, 1L);
            }
            trees.add(wide.build("000000000000000" + (request + 1)), ROUTE, false);
        }
        RouteTree route = trees.route(ROUTE);
        assertThat(route.executedMethods()).hasSize(RouteTrees.MAX_METHODS_PER_ROUTE);
        assertThat(route.methodsPartial()).isTrue();
    }

    @Test
    void withoutARequestTheFirstRequestIsUnknown() {
        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).build("0000000000000001"), ROUTE, false);
        RouteTree route = trees.route(ROUTE);
        assertThat(route.hasFirstRequest()).isTrue();
        assertThat(new RouteTree("GET /none", trees).hasFirstRequest()).isFalse();
        assertThat(new RouteTree("GET /none", trees).firstRequestNanos()).isEqualTo(-1L);
        assertThat(new RouteTree("GET /none", trees).firstRequestId()).isNull();
    }

    @Test
    void aTreeWithoutARouteIsCountedAndNotMerged() {
        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).build("0000000000000001"), RequestOutcome.UNKNOWN_ROUTE, false);
        trees.add(new Tree(0L).build("0000000000000002"), null, false);
        trees.add(new Tree(0L).build(null), ROUTE, false);
        assertThat(trees.routes()).isEmpty();
        assertThat(trees.unrouted()).isEqualTo(3);
    }

    @Test
    void anAssemblyOnlyRequestMarksItsRoute() {
        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).method(0, 1, 2, 1, 1_000L).build("0000000000000001"), ROUTE, false);
        trees.add(new Tree(1L).method(0, 1, 2, 1, 1_000L).build("0000000000000002"), ROUTE, false);
        assertThat(trees.route(ROUTE).assemblyOnly()).isFalse();
        trees.add(new Tree(2L).method(0, 1, 2, 1, 1_000L).build("0000000000000003"), ROUTE, true);
        assertThat(trees.route(ROUTE).assemblyOnly()).isTrue();
        assertThat(trees.route(ROUTE).assemblyRequests()).isEqualTo(1);
    }

    @Test
    void asynchronousWorkStaysUnderOneAsyncNodeAndOutOfTheHandler() {
        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).build("0000000000000000"), ROUTE, false);
        Tree tree = new Tree(1L).method(0, 1, 2, 1, 10_000L);
        int first = tree.async(5_000L);
        tree.method(first, 2, 0, 1, 5_000L);
        int second = tree.async(3_000L);
        tree.method(second, 2, 0, 1, 3_000L);
        trees.add(tree.build("0000000000000001"), ROUTE, false);

        RouteTree route = trees.route(ROUTE);
        int async = -1;
        for (int node = 1; node < route.nodeCount(); node++) {
            if (route.method(node) == RequestTree.ASYNC) {
                assertThat(async).as("one async node per parent").isEqualTo(-1);
                async = node;
            }
        }
        assertThat(route.calls(async)).isEqualTo(2);
        assertThat(route.totalNanos(async)).isEqualTo(8_000L);
        assertThat(route.handlerNanos()).as("only the handler's own method").isEqualTo(10_000L);
        for (int node = 1; node < route.nodeCount(); node++) {
            assertThat(route.async(node)).isEqualTo(route.method(node) != 1);
        }
    }

    @Test
    void theHistogramBucketsPerRequestTimeAndInterpolatesItsPercentiles() {
        assertThat(RouteTree.bucket(0L)).isZero();
        assertThat(RouteTree.bucket(999L)).isZero();
        assertThat(RouteTree.bucket(1_000L)).isEqualTo(1);
        assertThat(RouteTree.bucket(1_999L)).isEqualTo(1);
        assertThat(RouteTree.bucket(2_000L)).isEqualTo(2);
        assertThat(RouteTree.bucket(50_000_000L)).as("50,000 µs").isEqualTo(16);
        assertThat(RouteTree.bucket(Long.MAX_VALUE)).isEqualTo(RouteTree.BUCKETS - 1);
        for (int b = 1; b < RouteTree.BUCKETS - 1; b++) {
            assertThat(RouteTree.bucket(RouteTree.lowerNanos(b))).isEqualTo(b);
            assertThat(RouteTree.bucket(RouteTree.upperNanos(b) - 1)).isEqualTo(b);
        }

        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).method(0, 1, 2, 1, 1L).build("0000000000000000"), ROUTE, false);
        for (int i = 1; i <= 100; i++) {
            // The method runs 50 ms in each request, twice in the last ten.
            Tree tree = new Tree(i).method(0, 1, 2, i > 90 ? 2 : 1, i > 90 ? 100_000_000L : 50_000_000L);
            trees.add(tree.build(String.format("%016x", i)), ROUTE, false);
        }
        RouteTree route = trees.route(ROUTE);
        int[] histogram = route.histogram(1);
        assertThat(histogram[RouteTree.bucket(50_000_000L)]).isEqualTo(90);
        assertThat(histogram[RouteTree.bucket(100_000_000L)]).isEqualTo(10);
        assertThat(route.minNanos(1)).isEqualTo(50_000_000L);
        assertThat(route.maxNanos(1)).isEqualTo(100_000_000L);
        // Approximate within each bucket, whose bounds are clamped to the node's least and most time.
        assertThat(route.percentileNanos(1, 0.5)).isBetween(50_000_000L, RouteTree.upperNanos(16));
        assertThat(route.percentileNanos(1, 0.95)).isBetween(RouteTree.lowerNanos(17), 100_000_000L);
        assertThat(route.percentileNanos(1, 1.0)).isEqualTo(100_000_000L);
        assertThat(route.calls(1)).isEqualTo(110);
    }

    /** Within one bucket, the interpolation stays between the node's least and most time per request. */
    @Test
    void percentilesInterpolateOnlyBetweenTheNodesLeastAndMostTime() {
        RouteTrees trees = new RouteTrees();
        trees.add(new Tree(0L).method(0, 1, 2, 1, 1L).build("0000000000000000"), ROUTE, false);
        // 33 to 42 ms, all in the 32,768 to 65,536 µs bucket.
        for (int i = 1; i <= 10; i++) {
            Tree tree = new Tree(i).method(0, 1, 2, 1, (32L + i) * 1_000_000L);
            trees.add(tree.build(String.format("%016x", i)), ROUTE, false);
        }
        RouteTree route = trees.route(ROUTE);
        assertThat(RouteTree.bucket(33_000_000L)).isEqualTo(RouteTree.bucket(42_000_000L));
        assertThat(route.percentileNanos(1, 0.0)).isBetween(33_000_000L, 42_000_000L);
        assertThat(route.percentileNanos(1, 0.5)).isEqualTo(37_500_000L);
        assertThat(route.percentileNanos(1, 0.95)).isBetween(33_000_000L, 42_000_000L);
        assertThat(route.percentileNanos(2, 0.5)).as("an unknown node").isEqualTo(-1L);

        RouteTrees constant = new RouteTrees();
        for (int i = 0; i <= 10; i++) {
            Tree tree = new Tree(i).method(0, 1, 2, 1, 50_000_000L);
            constant.add(tree.build(String.format("%016x", i)), ROUTE, false);
        }
        assertThat(constant.route(ROUTE).percentileNanos(1, 0.5))
                .as("every request spent 50 ms in it, and no bucket bound beyond that is reported")
                .isEqualTo(50_000_000L);
        assertThat(constant.route(ROUTE).percentileNanos(1, 0.95)).isEqualTo(50_000_000L);
    }

    /**
     * {@code get_code_paths}' hottest nodes come from the whole route tree: a method far past the first
     * {@value CodePathsService#MAX_LIMIT} nodes of a depth-first page, under a parent with less total time, is found.
     */
    @Test
    void theHottestNodesAreFoundAnywhereInTheTree() {
        RouteTrees trees = new RouteTrees();
        for (int r = 0; r < 2; r++) {
            Tree tree = new Tree(r);
            int wide = tree.add(0, 1, 2, 1, 601_000L);
            for (int i = 0; i < 600; i++) {
                tree.method(wide, 100 + i, 2, 1, 1_000L);
            }
            int narrow = tree.add(0, 2, 2, 1, 200_000L);
            tree.method(narrow, 3, 2, 1, 199_000L);
            trees.add(tree.build(String.format("%016x", r)), ROUTE, false);
        }
        RouteTree route = trees.route(ROUTE);
        assertThat(CodePathsViews.order(route, CodePathsService.MAX_DEPTH).indexOf(route.nodeCount() - 1))
                .as("the hot method is past a page of the depth-first order")
                .isGreaterThan(CodePathsService.MAX_LIMIT);
        List<Integer> hottest = CodePathsViews.hottest(route, 3);
        assertThat(hottest).hasSize(3);
        assertThat(route.method(hottest.get(0))).isEqualTo(3);
        assertThat(route.method(hottest.get(1)))
                .as("the wide parent's own time")
                .isEqualTo(1);
        assertThat(CodePathsViews.hottest(route, 1_000)).hasSize(route.nodeCount() - 1);
    }

    /** Routes rank by their warm median, slowest first, a route without a warm request last. */
    @Test
    void routesRankByTheirWarmMedian() {
        RouteTrees trees = new RouteTrees();
        long[] medians = {30_000_000L, 90_000_000L, 60_000_000L};
        for (int r = 0; r < medians.length; r++) {
            for (int i = 0; i < 4; i++) {
                Tree tree = new Tree(i).method(0, 1, 2, 1, medians[r] + i);
                trees.add(tree.build(String.format("%08x%08x", r, i)), "GET /r" + r, false);
            }
        }
        trees.add(new Tree(0L).method(0, 1, 2, 1, 500_000_000L).build("00000000000000ff"), "GET /cold", false);
        assertThat(CodePathsViews.ranked(trees.routes()))
                .extracting(RouteTree::route)
                .containsExactly("GET /r1", "GET /r2", "GET /r0", "GET /cold");
    }

    @Test
    void theGlobalBudgetIsSharedAcrossRoutes() {
        RouteTrees trees = new RouteTrees(2_000, 120, 500);
        Random random = new Random(7);
        for (int r = 0; r < 6; r++) {
            for (int i = 0; i < 4; i++) {
                trees.add(randomTree(random, 60, 60, i), "GET /r" + r, false);
            }
        }
        assertThat(trees.nodes()).isLessThanOrEqualTo(120);
        int counted = 0;
        for (RouteTree route : trees.routes()) {
            counted += route.nodeCount();
            assertOneOtherPerParent(route);
            assertSelfConserved(route);
        }
        assertThat(counted).isEqualTo(trees.nodes());
    }

    /** Random request trees merged without budget pressure match the path-keyed reference exactly. */
    @Test
    void randomRequestTreesMatchTheReference() {
        for (long seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            RouteTrees trees = new RouteTrees();
            Reference reference = new Reference();
            int requests = 2 + random.nextInt(seed % 4 == 0 ? 40 : 6);
            int methods = 2 + random.nextInt(seed % 3 == 0 ? 40 : 6);
            for (int i = 0; i < requests; i++) {
                RequestTree tree = randomTree(random, methods, 1 + random.nextInt(30), i);
                trees.add(tree, ROUTE, false);
                if (i > 0) {
                    reference.add(tree);
                }
            }
            RouteTree route = trees.route(ROUTE);
            assertThat(route.warmRequests()).as("seed %d", seed).isEqualTo(requests - 1);
            assertThat(describe(route)).as("seed %d", seed).isEqualTo(reference.rows);
            assertThat(histograms(route)).as("seed %d", seed).isEqualTo(reference.histograms);
            assertThat(route.foldedCalls()).isZero();
            assertSelfConserved(route);
            assertOneOtherPerParent(route);
            for (int node = 0; node < route.nodeCount(); node++) {
                if (route.requests(node) == 0) {
                    continue;
                }
                long p50 = route.percentileNanos(node, 0.5);
                long p95 = route.percentileNanos(node, 0.95);
                assertThat(route.minNanos(node)).as("seed %d", seed).isLessThanOrEqualTo(p50);
                assertThat(p50).as("seed %d", seed).isLessThanOrEqualTo(p95);
                assertThat(p95).as("seed %d", seed).isLessThanOrEqualTo(route.maxNanos(node));
            }
        }
    }

    /** Under small node budgets, the caps hold, each parent keeps one Other node a phase, and self time is conserved. */
    @Test
    void underBudgetPressureTheCapsHoldAndSelfTimeIsConserved() {
        for (long seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            int perRoute = 70 + random.nextInt(100);
            RouteTrees trees = new RouteTrees(perRoute, perRoute + random.nextInt(200), 500);
            int requests = 2 + random.nextInt(30);
            for (int i = 0; i < requests; i++) {
                trees.add(randomTree(random, 200, 1 + random.nextInt(60), i), "GET /r" + random.nextInt(3), false);
            }
            for (RouteTree route : trees.routes()) {
                assertThat(route.nodeCount()).as("seed %d", seed).isLessThanOrEqualTo(perRoute);
                assertOneOtherPerParent(route);
                assertSelfConserved(route);
                for (int node = 1; node < route.nodeCount(); node++) {
                    assertThat(route.parent(node)).isLessThan(node);
                    assertThat(route.method(route.parent(node))).isNotEqualTo(RequestTree.OTHER);
                    assertThat(route.requests(node)).isLessThanOrEqualTo(route.warmRequests());
                }
            }
        }
    }

    // ---- helpers
    // ------------------------------------------------------------------------------------------------------

    private static void assertSelfConserved(RouteTree route) {
        long self = 0;
        for (int node = 0; node < route.nodeCount(); node++) {
            if (!route.async(node)) {
                self += route.selfNanos(node);
            }
        }
        assertThat(self)
                .as("the own nodes' self times add up to the requests' own time")
                .isEqualTo(route.ownNanos());
    }

    private static void assertOneOtherPerParent(RouteTree route) {
        Set<Long> parents = new HashSet<>();
        for (int node = 1; node < route.nodeCount(); node++) {
            if (route.method(node) == RequestTree.OTHER) {
                assertThat(parents.add(((long) route.parent(node) << 2) | route.phase(node)))
                        .as("one Other node per parent and phase")
                        .isTrue();
            }
        }
    }

    private static String path(RouteTree route, int node) {
        if (node == 0) {
            return "R";
        }
        return path(route, route.parent(node)) + "/" + label(route.method(node)) + "@" + route.phase(node);
    }

    private static String label(int method) {
        return method == RequestTree.ASYNC ? "A" : method == RequestTree.OTHER ? "O" : String.valueOf(method);
    }

    private static Map<String, List<Long>> describe(RouteTree route) {
        Map<String, List<Long>> rows = new HashMap<>();
        for (int node = 0; node < route.nodeCount(); node++) {
            rows.put(
                    path(route, node),
                    List.of(
                            route.requests(node),
                            route.calls(node),
                            route.totalNanos(node),
                            route.selfNanos(node),
                            (long) route.phase(node)));
        }
        return rows;
    }

    private static Map<String, List<Integer>> histograms(RouteTree route) {
        Map<String, List<Integer>> rows = new HashMap<>();
        for (int node = 0; node < route.nodeCount(); node++) {
            List<Integer> buckets = new ArrayList<>();
            for (int count : route.histogram(node)) {
                buckets.add(count);
            }
            rows.put(path(route, node), buckets);
        }
        return rows;
    }

    /**
     * A consistent random request tree, as {@link RequestTreeBuilder} builds one: unique methods per parent, up to two
     * asynchronous executions under the request, Other nodes as leaves, each node's children's total its child time.
     */
    static RequestTree randomTree(Random random, int methods, int nodes, long start) {
        Tree tree = new Tree(start);
        Map<Integer, Set<Integer>> used = new HashMap<>();
        List<Integer> open = new ArrayList<>(List.of(0));
        int asyncs = 0;
        for (int i = 0; i < nodes; i++) {
            int parent = open.get(random.nextInt(open.size()));
            if (parent == 0 && asyncs < 2 && random.nextInt(8) == 0) {
                open.add(tree.async(0L));
                asyncs++;
                continue;
            }
            Set<Integer> taken = used.computeIfAbsent(parent, ignored -> new HashSet<>());
            int method = random.nextInt(12) == 0 ? RequestTree.OTHER : random.nextInt(methods);
            if (!taken.add(method)) {
                continue;
            }
            int phase = tree.async[parent] ? 0 : random.nextInt(4);
            int node = tree.add(parent, method, phase, 1 + random.nextInt(4), 0L);
            if (method != RequestTree.OTHER && tree.depth.get(node) < 30) {
                open.add(node);
            }
        }
        // Totals bottom-up: each node its children's total plus its own self time; an execution's time is never the
        // request's own.
        for (int node = tree.size() - 1; node >= 1; node--) {
            long self = random.nextInt(4) == 0 ? 0L : random.nextInt(5_000_000);
            tree.total.set(node, tree.child.get(node) + self);
            int parent = tree.parent.get(node);
            if (tree.method.get(node) == RequestTree.ASYNC) {
                tree.asyncNanos += tree.total.get(node);
            } else {
                tree.child.set(parent, tree.child.get(parent) + tree.total.get(node));
            }
        }
        tree.total.set(0, tree.child.get(0) + random.nextInt(3_000_000));
        return tree.build(String.format("%016x", start + 1));
    }

    /** A request tree under construction. */
    static final class Tree {

        final long start;
        final List<Integer> parent = new ArrayList<>(List.of(-1));
        final List<Integer> method = new ArrayList<>(List.of(RequestTree.REQUEST));
        final List<Integer> phase = new ArrayList<>(List.of(0));
        final List<Long> calls = new ArrayList<>(List.of(1L));
        final List<Long> total = new ArrayList<>(List.of(0L));
        final List<Long> child = new ArrayList<>(List.of(0L));
        final List<Integer> depth = new ArrayList<>(List.of(0));
        boolean[] async = new boolean[4_096];
        long asyncNanos;

        Tree(long start) {
            this.start = start;
        }

        int size() {
            return parent.size();
        }

        /** A method node whose total, when given, is added to its parent's child time. */
        Tree method(int parentNode, int id, int nodePhase, long nodeCalls, long nodeTotal) {
            add(parentNode, id, nodePhase, nodeCalls, nodeTotal);
            return this;
        }

        int add(int parentNode, int id, int nodePhase, long nodeCalls, long nodeTotal) {
            int node = size();
            parent.add(parentNode);
            method.add(id);
            phase.add(nodePhase);
            calls.add(nodeCalls);
            total.add(nodeTotal);
            child.add(0L);
            depth.add(depth.get(parentNode) + 1);
            async[node] = async[parentNode] || id == RequestTree.ASYNC;
            if (nodeTotal > 0) {
                child.set(parentNode, child.get(parentNode) + (id == RequestTree.ASYNC ? 0L : nodeTotal));
                if (parentNode == 0 && id != RequestTree.ASYNC) {
                    total.set(0, Math.max(total.get(0), child.get(0)));
                }
            }
            return node;
        }

        int async(long nodeTotal) {
            asyncNanos += nodeTotal;
            return add(0, RequestTree.ASYNC, 0, 1, nodeTotal);
        }

        RequestTree build(String requestId) {
            int n = size();
            int[] parents = new int[n];
            int[] methods = new int[n];
            byte[] phases = new byte[n];
            long[] callsArray = new long[n];
            long[] totals = new long[n];
            long[] children = new long[n];
            for (int i = 0; i < n; i++) {
                parents[i] = parent.get(i);
                methods[i] = method.get(i);
                phases[i] = (byte) (int) phase.get(i);
                callsArray[i] = calls.get(i);
                totals[i] = total.get(i);
                children[i] = Math.min(child.get(i), totals[i]);
            }
            return new RequestTree(
                    requestId == null ? "exec-1" : requestId,
                    requestId,
                    1L,
                    1_000L + start,
                    start,
                    1,
                    asyncNanos,
                    0L,
                    false,
                    parents,
                    methods,
                    new long[n],
                    new int[n],
                    phases,
                    callsArray,
                    totals,
                    children);
        }
    }

    /** The merge by path, with maps: no budget. */
    static final class Reference {

        final Map<String, List<Long>> rows = new HashMap<>();
        final Map<String, List<Integer>> histograms = new HashMap<>();
        final Map<String, Long> phases = new LinkedHashMap<>();

        void add(RequestTree tree) {
            int n = tree.nodeCount();
            String[] paths = new String[n];
            Map<String, Long> perRequest = new LinkedHashMap<>();
            for (int node = 0; node < n; node++) {
                int method = tree.method()[node];
                long phase = method == RequestTree.ASYNC || isAsync(tree, node) ? 0L : (long) tree.phase()[node];
                // A node is its parent, method, and request phase.
                String path = node == 0 ? "R" : paths[tree.parent()[node]] + "/" + label(method) + "@" + phase;
                paths[node] = path;
                boolean under = node > 0 && tree.method()[tree.parent()[node]] == RequestTree.OTHER;
                if (under) {
                    continue;
                }
                long self = method == RequestTree.OTHER ? tree.total()[node] : tree.selfNanos(node);
                phases.putIfAbsent(path, phase);
                List<Long> row = rows.getOrDefault(path, List.of(0L, 0L, 0L, 0L, 0L));
                rows.put(
                        path,
                        List.of(
                                row.get(0),
                                row.get(1) + (node == 0 ? 1L : tree.calls()[node]),
                                row.get(2) + tree.total()[node],
                                row.get(3) + self,
                                phases.get(path)));
                perRequest.merge(path, tree.total()[node], Long::sum);
            }
            for (Map.Entry<String, Long> entry : perRequest.entrySet()) {
                List<Long> row = rows.get(entry.getKey());
                rows.put(entry.getKey(), List.of(row.get(0) + 1, row.get(1), row.get(2), row.get(3), row.get(4)));
                List<Integer> buckets = new ArrayList<>(histograms.getOrDefault(
                        entry.getKey(), new ArrayList<>(java.util.Collections.nCopies(RouteTree.BUCKETS, 0))));
                int bucket = RouteTree.bucket(entry.getValue());
                buckets.set(bucket, buckets.get(bucket) + 1);
                histograms.put(entry.getKey(), buckets);
            }
        }

        private static boolean isAsync(RequestTree tree, int node) {
            for (int current = node; current > 0; current = tree.parent()[current]) {
                if (tree.method()[current] == RequestTree.ASYNC) {
                    return true;
                }
            }
            return false;
        }
    }
}
