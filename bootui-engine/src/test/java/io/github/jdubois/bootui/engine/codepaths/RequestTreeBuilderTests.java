package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The engine's request-tree merge ({@code docs/PLAN-v2.md} §5.14, M5-4a): examples, then seeded random fragments
 * against a straightforward path-keyed reference of the merge, the Other bucket, and the depth cap, and a brute-force
 * reference of the interval union.
 */
class RequestTreeBuilderTests {

    private static final long REQUEST = 0xabL;

    @Test
    void theRequestsOwnFragmentsMergeByMethodAndHandoffsStayApart() {
        RequestTreeBuilder builder = new RequestTreeBuilder("00000000000000ab", "00000000000000ab");
        // An application filter outside the scope, then the handler's fragment.
        builder.add(Blobs.request(1L, REQUEST)
                .between(0L, 100L)
                .node(-1, 1, 1, 1L, 100L, 0L)
                .fragment());
        builder.add(Blobs.request(1L, REQUEST)
                .between(100L, 1_100L)
                .node(-1, 2, 2, 1L, 1_000L, 600L)
                .node(0, 3, 2, 2L, 600L, 0L)
                .fragment());
        // Two handoffs of one execution overlapping each other, then another execution.
        builder.add(Blobs.handoff(1L, REQUEST, 0xc1L)
                .between(200L, 700L)
                .node(-1, 4, 0, 1L, 500L, 0L)
                .fragment());
        builder.add(Blobs.handoff(1L, REQUEST, 0xc1L)
                .between(500L, 900L)
                .node(-1, 4, 0, 1L, 400L, 0L)
                .fragment());
        builder.add(Blobs.handoff(1L, REQUEST, 0xc2L)
                .between(1_000L, 1_500L)
                .node(-1, 5, 0, 1L, 500L, 0L)
                .fragment());

        RequestTree tree = builder.build();

        assertThat(describe(tree))
                .containsExactly(
                        "-1 REQUEST calls=2 total=1100 child=1100",
                        "0 1 calls=1 total=100 child=0",
                        "0 2 calls=1 total=1000 child=600",
                        "2 3 calls=2 total=600 child=0",
                        "0 ASYNC(c1) calls=2 total=700 child=700",
                        "4 4 calls=2 total=900 child=0",
                        "0 ASYNC(c2) calls=1 total=500 child=500",
                        "6 5 calls=1 total=500 child=0");
        assertThat(tree.durationNanos())
                .as("the request's own time, async aside")
                .isEqualTo(1_100L);
        assertThat(tree.asyncNanos()).as("[200, 900) and [1000, 1500)").isEqualTo(1_200L);
        assertThat(tree.selfNanos(0)).isZero();
        assertThat(tree.phase()[2]).isEqualTo((byte) 2);
        assertThat(tree.fragments()).isEqualTo(5);
    }

    @Test
    void overlappingFragmentsOfTheRequestNeverMakeItsSelfTimeNegative() {
        RequestTreeBuilder builder = new RequestTreeBuilder("k", null);
        builder.add(Blobs.request(1L, REQUEST)
                .between(0L, 1_000L)
                .node(-1, 1, 0, 1L, 1_000L, 0L)
                .fragment());
        builder.add(Blobs.request(1L, REQUEST)
                .between(500L, 1_500L)
                .node(-1, 2, 0, 1L, 1_000L, 0L)
                .fragment());

        RequestTree tree = builder.build();

        assertThat(tree.durationNanos()).isEqualTo(1_500L);
        assertThat(tree.child()[0]).isEqualTo(1_500L);
        assertThat(tree.selfNanos(0)).isZero();
    }

    @Test
    void pastTheBudgetEachParentGetsOneOtherNodeAndTheRestStaysInTheParent() {
        RequestTreeBuilder builder = new RequestTreeBuilder("k", null);
        // 600 distinct top-level methods across fragments, each with a callee.
        for (int i = 0; i < 600; i++) {
            builder.add(Blobs.request(1L, REQUEST)
                    .between(i * 10L, i * 10L + 10L)
                    .node(-1, 1_000 + i, 0, 1L, 10L, 4L)
                    .node(0, 5_000 + i, 0, 1L, 4L, 0L)
                    .fragment());
        }

        RequestTree tree = builder.build();

        assertThat(tree.nodeCount()).isLessThanOrEqualTo(RequestTreeBuilder.MAX_NODES);
        Map<Integer, Long> otherCalls = new LinkedHashMap<>();
        for (int node = 0; node < tree.nodeCount(); node++) {
            if (tree.method()[node] == RequestTree.OTHER) {
                assertThat(otherCalls.put(tree.parent()[node], tree.calls()[node]))
                        .as("one Other node per parent")
                        .isNull();
                assertThat(tree.child()[node])
                        .as("calls under Other stay in it")
                        .isZero();
            }
        }
        // The request's node, then 240 methods with their callee but the last: past 480 nodes, the request's Other
        // takes the 360 methods left, and the 240th method's Other its callee.
        assertThat(otherCalls).containsEntry(0, 360L).hasSize(2);
        assertThat(tree.child()[0])
                .as("every top-level method's time is the request's children's")
                .isEqualTo(6_000L);
        assertInvariants(tree);
    }

    @Test
    void aHandoffDeeperThanTheCapLeavesItsTimeInItsParent() {
        Blobs blobs = Blobs.handoff(1L, REQUEST, 0xc1L).between(0L, 3_200L);
        for (int level = 0; level < 32; level++) {
            blobs.node(level - 1, level + 1, 0, 1L, 3_200L - level * 100L, level == 31 ? 0L : 3_100L - level * 100L);
        }
        RequestTreeBuilder builder = new RequestTreeBuilder("k", null);
        builder.add(blobs.fragment());

        RequestTree tree = builder.build();

        // The execution's node takes one level: the fragment's deepest node folds into its parent.
        assertThat(tree.nodeCount()).isEqualTo(1 + 1 + 31);
        int deepest = tree.nodeCount() - 1;
        assertThat(tree.child()[deepest]).isZero();
        assertThat(tree.selfNanos(deepest)).isEqualTo(tree.total()[deepest]);
        assertThat(tree.droppedCalls()).isEqualTo(1L);
        assertInvariants(tree);
    }

    @Test
    void randomFragmentsMatchTheReference() {
        for (long seed = 1; seed <= 400; seed++) {
            Random random = new Random(seed);
            RequestTreeBuilder builder = new RequestTreeBuilder("k", null);
            Reference reference = new Reference();
            int fragments = 1 + random.nextInt(seed % 4 == 0 ? 60 : 6);
            int methods = 2 + random.nextInt(seed % 3 == 0 ? 300 : 8);
            List<long[]> own = new ArrayList<>();
            Map<Long, List<long[]>> handoffs = new LinkedHashMap<>();
            for (int f = 0; f < fragments; f++) {
                long start = random.nextInt(1_000);
                long end = start + random.nextInt(500);
                boolean handoff = random.nextInt(3) == 0;
                long execution = 0xc0L + random.nextInt(3);
                Blobs blobs = handoff
                        ? Blobs.handoff(1L, REQUEST, execution).between(start, end)
                        : Blobs.request(1L, REQUEST).between(start, end);
                randomNodes(random, blobs, methods, seed % 5 == 0 ? 34 : 6);
                CodePathFragment fragment = blobs.fragment();
                assertThat(fragment).as("seed %d", seed).isNotNull();
                builder.add(fragment);
                reference.add(fragment);
                if (handoff) {
                    handoffs.computeIfAbsent(execution, ignored -> new ArrayList<>())
                            .add(new long[] {start, end});
                } else {
                    own.add(new long[] {start, end});
                }
            }

            RequestTree tree = builder.build();

            assertThat(describe(tree)).as("seed %d", seed).containsExactlyElementsOf(reference.describe(tree));
            assertThat(tree.droppedCalls()).as("seed %d", seed).isEqualTo(reference.dropped);
            assertThat(tree.durationNanos()).as("seed %d", seed).isEqualTo(bruteForceUnion(own));
            List<long[]> allAsync = new ArrayList<>();
            handoffs.values().forEach(allAsync::addAll);
            assertThat(tree.asyncNanos()).as("seed %d", seed).isEqualTo(bruteForceUnion(allAsync));
            assertInvariants(tree);
        }
    }

    /**
     * A settled tree keeps only its tree and spans: resuming from them, as a late fragment does, merges exactly as the
     * builder that built the tree would have, whatever the fragments and wherever the cut.
     */
    @Test
    void resumingFromATreeAndItsSpansMergesAsTheOriginalBuilder() {
        for (long seed = 1; seed <= 400; seed++) {
            Random random = new Random(seed);
            RequestTreeBuilder whole = new RequestTreeBuilder("k", "00000000000000ab");
            RequestTreeBuilder resumed = new RequestTreeBuilder("k", "00000000000000ab");
            int fragments = 2 + random.nextInt(seed % 4 == 0 ? 60 : 6);
            int methods = 2 + random.nextInt(seed % 3 == 0 ? 300 : 8);
            for (int f = 0; f < fragments; f++) {
                long start = random.nextInt(1_000);
                long end = start + random.nextInt(500);
                Blobs blobs = random.nextInt(3) == 0
                        ? Blobs.handoff(1L, REQUEST, 0xc0L + random.nextInt(3)).between(start, end)
                        : Blobs.request(1L, REQUEST).between(start, end);
                randomNodes(random, blobs, methods, seed % 5 == 0 ? 34 : 6);
                CodePathFragment fragment = blobs.fragment();
                whole.add(fragment);
                if (f > 0 && random.nextInt(3) == 0) {
                    RequestTree settled = resumed.build();
                    resumed = RequestTreeBuilder.resume(settled, resumed.spans());
                }
                resumed.add(fragment);
            }

            RequestTree expected = whole.build();
            RequestTree actual = resumed.build();
            assertThat(actual).as("seed %d", seed).usingRecursiveComparison().isEqualTo(expected);
            assertThat(resumed.spans())
                    .as("seed %d", seed)
                    .usingRecursiveComparison()
                    .isEqualTo(whole.spans());
        }
    }

    @Test
    void theUnionMatchesABruteForceUnion() {
        for (long seed = 1; seed <= 500; seed++) {
            Random random = new Random(seed);
            Intervals intervals = new Intervals();
            List<long[]> list = new ArrayList<>();
            for (int i = 0; i < random.nextInt(30); i++) {
                long start = random.nextInt(2_000);
                long end = start + random.nextInt(300) - 20;
                intervals.add(start, end);
                list.add(new long[] {start, end});
            }
            assertThat(intervals.union()).as("seed %d", seed).isEqualTo(bruteForceUnion(list));
        }
    }

    // ---- the reference --------------------------------------------------------------------------------------------

    /** Nodes in pre-order, as the bridge creates them: each with a random parent among the earlier ones. */
    private static void randomNodes(Random random, Blobs blobs, int methods, int maxDepth) {
        int count = 1 + random.nextInt(maxDepth > 6 ? 40 : 12);
        int[] depths = new int[count];
        long[][] values = new long[count][];
        int[] parents = new int[count];
        for (int node = 0; node < count; node++) {
            int parent = node == 0 ? -1 : random.nextInt(4) == 0 ? -1 : random.nextInt(node);
            if (parent >= 0 && depths[parent] >= maxDepth) {
                parent = -1;
            }
            parents[node] = parent;
            depths[node] = parent < 0 ? 1 : depths[parent] + 1;
            int method = random.nextInt(10) == 0 ? CodePathFragment.OTHER : random.nextInt(methods);
            values[node] = new long[] {method, random.nextInt(4), 1 + random.nextInt(5), 0L, 0L};
        }
        // Totals bottom-up, so each node's children's total never exceeds its own.
        for (int node = count - 1; node >= 0; node--) {
            values[node][3] = values[node][4] + random.nextInt(1_000);
            if (parents[node] >= 0) {
                values[parents[node]][4] += values[node][3];
            }
        }
        for (int node = 0; node < count; node++) {
            blobs.node(
                    parents[node],
                    (int) values[node][0],
                    (int) values[node][1],
                    values[node][2],
                    values[node][3],
                    values[node][4]);
        }
    }

    /** The merge by path, with maps, applying the same budget, Other, and depth rules. */
    static final class Reference {

        final Map<String, long[]> nodes = new LinkedHashMap<>();
        final Map<String, Integer> depth = new HashMap<>();
        long dropped;

        Reference() {
            nodes.put("R", new long[3]);
            depth.put("R", 0);
        }

        void add(CodePathFragment fragment) {
            dropped += fragment.dropped();
            String attach = "R";
            if (fragment.handoff()) {
                attach = "R/A" + Long.toHexString(fragment.execution());
                if (!nodes.containsKey(attach)) {
                    if (nodes.size() >= RequestTreeBuilder.MAX_NODES - RequestTreeBuilder.OTHER_RESERVE) {
                        for (long calls : fragment.calls()) {
                            dropped += calls;
                        }
                        return;
                    }
                    nodes.put(attach, new long[3]);
                    depth.put(attach, 1);
                }
            }
            String[] paths = new String[fragment.nodeCount()];
            for (int node = 0; node < fragment.nodeCount(); node++) {
                int parent = fragment.parent()[node];
                long calls = fragment.calls()[node];
                long total = fragment.total()[node];
                if (parent >= 0 && paths[parent] == null) {
                    dropped += calls;
                    continue;
                }
                String target = parent < 0 ? attach : paths[parent];
                if (target.endsWith("/O")) {
                    dropped += calls;
                    continue;
                }
                String path = null;
                if (depth.get(target) + 1 <= RequestTreeBuilder.MAX_DEPTH) {
                    int method = fragment.method()[node];
                    String own = target + "/" + method;
                    String other = target + "/O";
                    if (method != CodePathFragment.OTHER && nodes.containsKey(own)) {
                        path = own;
                    } else if (method != CodePathFragment.OTHER
                            && nodes.size() < RequestTreeBuilder.MAX_NODES - RequestTreeBuilder.OTHER_RESERVE) {
                        path = own;
                    } else if (nodes.containsKey(other) || nodes.size() < RequestTreeBuilder.MAX_NODES) {
                        path = other;
                    }
                }
                if (path == null) {
                    if (parent >= 0) {
                        long[] values = nodes.get(target);
                        values[2] = Math.max(0L, values[2] - total);
                    }
                    dropped += calls;
                    continue;
                }
                long[] values = nodes.computeIfAbsent(path, ignored -> new long[3]);
                depth.put(path, depth.get(target) + 1);
                values[0] += calls;
                values[1] += total;
                if (!path.endsWith("/O")) {
                    values[2] += fragment.child()[node];
                }
                if (parent < 0) {
                    nodes.get(target)[2] += total;
                }
                paths[node] = path;
            }
        }

        /** Calls and children per node, with the totals the union decides taken from {@code tree}. */
        List<String> describe(RequestTree tree) {
            List<String> rows = new ArrayList<>();
            Map<String, Integer> index = new HashMap<>();
            int node = 0;
            for (Map.Entry<String, long[]> entry : nodes.entrySet()) {
                String path = entry.getKey();
                index.put(path, node);
                long[] values = entry.getValue();
                String label;
                int parent;
                if (path.equals("R")) {
                    label = "REQUEST";
                    parent = -1;
                } else {
                    String last = path.substring(path.lastIndexOf('/') + 1);
                    parent = index.get(path.substring(0, path.lastIndexOf('/')));
                    label = last.equals("O")
                            ? "OTHER"
                            : last.startsWith("A") ? "ASYNC(" + last.substring(1) + ")" : last;
                }
                boolean union = path.equals("R")
                        || path.substring(path.lastIndexOf('/') + 1).startsWith("A");
                long total = union ? tree.total()[node] : values[1];
                long calls = union ? tree.calls()[node] : values[0];
                rows.add(parent + " " + label + " calls=" + calls + " total=" + total + " child="
                        + Math.min(values[2], total));
                node++;
            }
            return rows;
        }
    }

    // ---- helpers
    // ------------------------------------------------------------------------------------------------------

    static long bruteForceUnion(List<long[]> intervals) {
        boolean[] covered = new boolean[4_000];
        for (long[] interval : intervals) {
            for (long t = interval[0]; t < interval[1]; t++) {
                covered[(int) t] = true;
            }
        }
        long total = 0;
        for (boolean point : covered) {
            total += point ? 1 : 0;
        }
        return total;
    }

    static List<String> describe(RequestTree tree) {
        List<String> rows = new ArrayList<>();
        for (int node = 0; node < tree.nodeCount(); node++) {
            int method = tree.method()[node];
            String label = method == RequestTree.REQUEST
                    ? "REQUEST"
                    : method == RequestTree.ASYNC
                            ? "ASYNC(" + Long.toHexString(tree.execution()[node]) + ")"
                            : method == RequestTree.OTHER ? "OTHER" : String.valueOf(method);
            rows.add(tree.parent()[node] + " " + label + " calls=" + tree.calls()[node] + " total=" + tree.total()[node]
                    + " child=" + tree.child()[node]);
        }
        return rows;
    }

    static void assertInvariants(RequestTree tree) {
        for (int node = 0; node < tree.nodeCount(); node++) {
            assertThat(tree.parent()[node]).isLessThan(node);
            assertThat(tree.child()[node]).isBetween(0L, tree.total()[node]);
            assertThat(tree.selfNanos(node)).isNotNegative();
        }
        assertThat(tree.nodeCount()).isLessThanOrEqualTo(RequestTreeBuilder.MAX_NODES);
    }
}
