package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Property-based tests of §5.4's three algorithms ({@code docs/PLAN-v2.md}), each checked against a brute-force
 * reference over many seeded random inputs, so a failure names a reproducible seed.
 */
class ModelAlgorithmPropertyTests {

    private static final int RUNS = 300;

    @Test
    void reverseClosureFindsExactlyTheNodesWithAnAllowedBackwardPathAndTheirShortestDepth() {
        for (int seed = 0; seed < RUNS; seed++) {
            Random random = new Random(seed);
            RuntimeModel model = randomModel(random, 2 + random.nextInt(14), random.nextInt(40));
            Set<EdgeType> allowed = randomEdgeTypes(random);
            int start = random.nextInt(model.nodes().size());
            int depth = 1 + random.nextInt(ReverseClosure.MAX_DEPTH);

            Map<Integer, Integer> closure = ReverseClosure.of(model, start, allowed, depth);

            assertThat(closure).as("seed %d", seed).isEqualTo(bruteForce(model, start, allowed, depth));
            assertThat(closure).as("seed %d", seed).doesNotContainKey(start);
            Map<Integer, Integer> deeper = ReverseClosure.of(model, start, allowed, ReverseClosure.MAX_DEPTH);
            assertThat(deeper.keySet()).as("monotonic in depth, seed %d", seed).containsAll(closure.keySet());
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReverseClosure.of(randomModel(new Random(1), 2, 1), 0, Set.of(), 6));
    }

    @Test
    void intervalUnionCoversExactlyTheInstantsItsIntervalsCoverWhateverTheirOrder() {
        for (int seed = 0; seed < RUNS; seed++) {
            Random random = new Random(seed);
            List<long[]> intervals = new ArrayList<>();
            for (int i = random.nextInt(12); i > 0; i--) {
                long start = random.nextInt(100);
                intervals.add(new long[] {start, start + random.nextInt(30) - 5});
            }
            Set<Long> covered = new HashSet<>();
            long longest = 0;
            long sum = 0;
            for (long[] interval : intervals) {
                for (long t = interval[0]; t < interval[1]; t++) {
                    covered.add(t);
                }
                longest = Math.max(longest, interval[1] - interval[0]);
                sum += Math.max(0, interval[1] - interval[0]);
            }

            List<long[]> union = IntervalUnion.of(intervals);
            long length = IntervalUnion.length(intervals);

            assertThat(length).as("seed %d", seed).isEqualTo(covered.size()).isBetween(longest, sum);
            for (int i = 1; i < union.size(); i++) {
                assertThat(union.get(i)[0])
                        .as("disjoint and sorted, seed %d", seed)
                        .isGreaterThan(union.get(i - 1)[1]);
            }
            List<long[]> shuffled = new ArrayList<>(intervals);
            java.util.Collections.shuffle(shuffled, random);
            assertThat(IntervalUnion.length(shuffled))
                    .as("order-invariant, seed %d", seed)
                    .isEqualTo(length);
            assertThat(IntervalUnion.length(union))
                    .as("idempotent, seed %d", seed)
                    .isEqualTo(length);
        }
    }

    @Test
    void edgeDiffPartitionsTwoRunsEdgesIntoAddedRemovedAndShared() {
        for (int seed = 0; seed < RUNS; seed++) {
            Random random = new Random(seed);
            RuntimeModel before = randomModel(random, 2 + random.nextInt(8), random.nextInt(25));
            RuntimeModel after = randomModel(random, 2 + random.nextInt(8), random.nextInt(25));

            EdgeDiff diff = EdgeDiff.between(before, after, Provenance.OBSERVED);
            Set<EdgeDiff.EdgeRef> earlier = EdgeDiff.refs(before, Provenance.OBSERVED);
            Set<EdgeDiff.EdgeRef> later = EdgeDiff.refs(after, Provenance.OBSERVED);
            Set<EdgeDiff.EdgeRef> shared = new HashSet<>(earlier);
            shared.retainAll(later);

            Set<EdgeDiff.EdgeRef> rebuiltLater = new HashSet<>(diff.added());
            rebuiltLater.addAll(shared);
            Set<EdgeDiff.EdgeRef> rebuiltEarlier = new HashSet<>(diff.removed());
            rebuiltEarlier.addAll(shared);
            assertThat(rebuiltLater).as("seed %d", seed).isEqualTo(later);
            assertThat(rebuiltEarlier).as("seed %d", seed).isEqualTo(earlier);
            assertThat(diff.added().stream().noneMatch(earlier::contains))
                    .as("seed %d", seed)
                    .isTrue();
            assertThat(EdgeDiff.between(before, before, Provenance.OBSERVED).added())
                    .isEmpty();
            EdgeDiff reverse = EdgeDiff.between(after, before, Provenance.OBSERVED);
            assertThat(reverse.added()).as("symmetric, seed %d", seed).isEqualTo(diff.removed());
        }
    }

    private static RuntimeModel randomModel(Random random, int nodes, int edges) {
        RuntimeModelBuilder builder = new RuntimeModelBuilder();
        NodeType[] types = NodeType.values();
        for (int i = 0; i < nodes; i++) {
            builder.node(types[random.nextInt(types.length)], "n" + i);
        }
        EdgeType[] edgeTypes = EdgeType.values();
        for (int i = 0; i < edges; i++) {
            int from = random.nextInt(nodes);
            int to = random.nextInt(nodes);
            EdgeType type = edgeTypes[random.nextInt(edgeTypes.length)];
            if (random.nextBoolean()) {
                builder.observe(from, type, to, random.nextInt(1_000));
            } else {
                builder.declare(from, type, to);
            }
        }
        return builder.build("run", List.of());
    }

    private static Set<EdgeType> randomEdgeTypes(Random random) {
        Set<EdgeType> allowed = EnumSet.noneOf(EdgeType.class);
        for (EdgeType type : EdgeType.values()) {
            if (random.nextInt(3) > 0) {
                allowed.add(type);
            }
        }
        return allowed;
    }

    /** Every node with a backward path to {@code start}, by exhaustive search of every path up to {@code depth}. */
    private static Map<Integer, Integer> bruteForce(RuntimeModel model, int start, Set<EdgeType> allowed, int depth) {
        Map<Integer, Integer> shortest = new HashMap<>();
        walk(model, start, start, allowed, depth, 0, shortest, new HashSet<>(Set.of(start)));
        return shortest;
    }

    private static void walk(
            RuntimeModel model,
            int start,
            int node,
            Set<EdgeType> allowed,
            int depth,
            int steps,
            Map<Integer, Integer> shortest,
            Set<Integer> onPath) {
        if (steps == depth) {
            return;
        }
        for (ModelEdge edge : model.incoming(node)) {
            if (!allowed.contains(edge.type()) || onPath.contains(edge.from())) {
                continue;
            }
            shortest.merge(edge.from(), steps + 1, Math::min);
            onPath.add(edge.from());
            walk(model, start, edge.from(), allowed, depth, steps + 1, shortest, onPath);
            onPath.remove(edge.from());
        }
    }
}
