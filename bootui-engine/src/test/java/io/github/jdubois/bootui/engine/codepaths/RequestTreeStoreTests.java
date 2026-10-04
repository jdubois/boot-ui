package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RequestTreeStoreTests {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aTreeSettlesAfterItsQuietPeriodAndKeepsItsRoutesExemplars() {
        Map<String, RequestOutcome> outcomes = new HashMap<>();
        outcomes.put(CodePathFragment.hex(1L), new RequestOutcome("GET /orders", 200, false));
        outcomes.put(CodePathFragment.hex(2L), new RequestOutcome("GET /orders", 500, true));
        outcomes.put(CodePathFragment.hex(3L), new RequestOutcome("GET /orders", 200, false));
        RequestTreeStore store = new RequestTreeStore(named(outcomes));

        store.add(fragment(1L, 1_000L), 0L);
        store.add(fragment(2L, 5_000L), 0L);
        store.add(fragment(3L, 3_000L), 0L);
        assertThat(store.tree(CodePathFragment.hex(1L)))
                .as("an open tree reads too")
                .isNotNull();
        assertThat(store.settle(SECOND)).isZero();
        assertThat(store.settle(2 * SECOND)).isEqualTo(3);

        assertThat(store.recent())
                .extracting(RequestTree::requestId)
                .containsExactly(CodePathFragment.hex(3L), CodePathFragment.hex(2L), CodePathFragment.hex(1L));
        assertThat(store.exemplars("GET /orders"))
                .extracting(RequestTree::durationNanos)
                .as("the slowest, then the failed")
                .containsExactly(5_000L, 3_000L, 1_000L, 5_000L);
        assertThat(store.routes()).containsExactly("GET /orders");
        assertThat(store.openCount()).isZero();
        assertThat(store.settled()).isEqualTo(3L);
    }

    @Test
    void aLateHandoffMergesIntoItsSettledTree() {
        RequestTreeStore store = new RequestTreeStore(null);
        store.add(fragment(1L, 1_000L), 0L);
        store.settle(2 * SECOND);

        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 400L)
                        .node(-1, 9, 0, 1L, 400L, 0L)
                        .fragment(),
                3 * SECOND);

        RequestTree tree = store.tree(CodePathFragment.hex(1L));
        assertThat(tree.fragments()).isEqualTo(2);
        assertThat(tree.asyncNanos()).isEqualTo(400L);
        assertThat(store.late()).isEqualTo(1L);
        assertThat(store.exemplars(RequestOutcome.UNKNOWN_ROUTE))
                .singleElement()
                .as("the exemplar is the merged tree too")
                .isSameAs(tree);
        assertThat(store.keptNodes()).isEqualTo(2 * tree.nodeCount());
    }

    @Test
    void aLateFragmentForATreeOnlyAnExemplarStillKeepsIsDroppedRatherThanDuplicated() {
        RequestTreeStore store = new RequestTreeStore(null);
        store.add(fragment(1L, 1_000_000L), 0L);
        store.settle(2 * SECOND);
        for (long request = 2; request <= RequestTreeStore.RECENT + 1; request++) {
            store.add(fragment(request, 10L), 2 * SECOND);
        }
        store.settle(4 * SECOND);
        assertThat(store.recent()).extracting(RequestTree::requestId).doesNotContain(CodePathFragment.hex(1L));
        int kept = store.keptNodes();

        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 400L)
                        .node(-1, 9, 0, 1L, 400L, 0L)
                        .fragment(),
                5 * SECOND);
        store.settleAll();

        assertThat(store.lateDropped()).isEqualTo(1L);
        assertThat(store.openCount()).isZero();
        assertThat(store.keptNodes()).isEqualTo(kept);
        assertThat(store.exemplars(RequestOutcome.UNKNOWN_ROUTE))
                .extracting(RequestTree::requestId)
                .containsOnlyOnce(CodePathFragment.hex(1L));
        assertThat(store.tree(CodePathFragment.hex(1L)).fragments()).isEqualTo(1);
    }

    @Test
    void openTreesAndKeptTreesStayBounded() {
        RequestTreeStore store = new RequestTreeStore(null);
        for (long request = 1; request <= RequestTreeStore.MAX_OPEN + RequestTreeStore.RECENT + 10; request++) {
            store.add(fragment(request, request), 0L);
        }

        assertThat(store.openCount()).isEqualTo(RequestTreeStore.MAX_OPEN);
        store.settleAll();
        assertThat(store.openCount()).isZero();
        assertThat(store.recent()).hasSize(RequestTreeStore.RECENT);
        assertThat(store.forgotten()).isPositive();
        assertThat(store.keptNodes()).isLessThanOrEqualTo(RequestTreeStore.MAX_KEPT_NODES);
    }

    @Test
    void aFailingOutcomeLookupKeepsTheTreeUnderTheUnknownRoute() {
        RequestTreeStore store = new RequestTreeStore(ids -> {
            throw new IllegalStateException("journal unavailable");
        });
        store.add(fragment(1L, 10L), 0L);
        store.settleAll();

        assertThat(store.exemplars(RequestOutcome.UNKNOWN_ROUTE)).hasSize(1);
    }

    private static Function<Set<String>, Map<String, RequestOutcome>> named(Map<String, RequestOutcome> outcomes) {
        return ids -> {
            Map<String, RequestOutcome> found = new HashMap<>();
            for (String id : ids) {
                if (outcomes.containsKey(id)) {
                    found.put(id, outcomes.get(id));
                }
            }
            return found;
        };
    }

    private static CodePathFragment fragment(long request, long duration) {
        return Blobs.request(1L, request)
                .between(0L, duration)
                .node(-1, 1, 2, 1L, duration, 0L)
                .fragment();
    }
}
