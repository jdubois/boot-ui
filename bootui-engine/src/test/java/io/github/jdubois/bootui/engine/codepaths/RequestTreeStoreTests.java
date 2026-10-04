package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        RequestTreeStore store = new RequestTreeStore(ids -> everyRequest("GET /a", ids));
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
        assertThat(store.exemplars("GET /a"))
                .singleElement()
                .as("the exemplar is the merged tree too")
                .isSameAs(tree);
        assertThat(store.keptNodes()).isEqualTo(2 * tree.nodeCount());
    }

    @Test
    void aLateFragmentForATreeOnlyAnExemplarStillKeepsIsDroppedRatherThanDuplicated() {
        RequestTreeStore store = new RequestTreeStore(ids -> everyRequest("GET /a", ids));
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
        assertThat(store.exemplars("GET /a"))
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

    /**
     * M52-05: a tree that settles before its request's exchange is recorded, as a slow reactive response's, waits for
     * it, through reads and other traffic, and joins its route exactly once, with its calls, when the exchange arrives.
     */
    @Test
    void aTreeSettledBeforeItsExchangeJoinsItsRouteOnceTheExchangeArrives() {
        Map<String, RequestOutcome> outcomes = new HashMap<>();
        List<String> announced = new ArrayList<>();
        RequestTreeStore store = new RequestTreeStore(named(outcomes));
        store.onSettled((tree, outcome) -> announced.add(tree.requestId() + " " + outcome.route()));
        String slow = CodePathFragment.hex(1L);
        store.add(
                Blobs.request(1L, 1L)
                        .between(0L, 1_000L)
                        .sequence(4L)
                        .node(-1, 1, 2, 1L, 1_000L, 0L)
                        .fragment(),
                0L);

        assertThat(store.settle(2 * SECOND)).isEqualTo(1);
        store.settle(3 * SECOND);
        assertThat(announced).as("no route yet, so not handed over").isEmpty();
        assertThat(store.unresolvedCount()).isEqualTo(1);
        assertThat(store.recent()).extracting(RequestTree::requestId).containsExactly(slow);
        assertThat(store.exemplars(RequestOutcome.UNKNOWN_ROUTE)).isEmpty();
        // A late fragment still merges while it waits.
        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 50L)
                        .node(-1, 9, 0, 1L, 50L, 0L)
                        .fragment(),
                4 * SECOND);

        outcomes.put(
                slow,
                new RequestOutcome(
                        "GET /stream",
                        200,
                        false,
                        List.of(new RequestOutcome.StampedCall(CodePathStamps.SQL, CodePathStamps.pack(4L, 0, 1), 7L)),
                        0L));
        store.settle(4 * SECOND + RequestTreeStore.RESOLVE_EVERY_NANOS);
        store.settle(6 * SECOND);

        assertThat(announced).containsExactly(slow + " GET /stream");
        assertThat(store.unresolvedCount()).isZero();
        assertThat(store.lateNamed()).isEqualTo(1L);
        assertThat(store.exemplars("GET /stream")).singleElement().satisfies(tree -> {
            assertThat(tree.fragments()).isEqualTo(2);
            assertThat(tree.ioCalls(1, CodePathStamps.SQL))
                    .as("its calls, once")
                    .isEqualTo(1L);
        });
        assertThat(store.outcome(slow).route()).isEqualTo("GET /stream");
    }

    @Test
    void aTreeWhoseExchangeNeverArrivesIsHandedOverUnknownOnceAfterTheWindow() {
        List<String> announced = new ArrayList<>();
        RequestTreeStore store = new RequestTreeStore(ids -> Map.of());
        store.onSettled((tree, outcome) -> announced.add(outcome.route()));
        store.add(fragment(1L, 10L), 0L);
        // Work only an execution owns has no exchange to wait for.
        store.add(
                Blobs.handoff(1L, 0L, 0xc2L)
                        .between(0L, 5L)
                        .node(-1, 9, 0, 1L, 5L, 0L)
                        .fragment(),
                0L);

        store.settle(2 * SECOND);
        assertThat(announced).containsExactly(RequestOutcome.UNKNOWN_ROUTE);
        store.settle(2 * SECOND + RequestTreeStore.RESOLVE_NANOS - 1);
        assertThat(announced).hasSize(1);
        // Looked up again less often as it waits, so given up at most one back-off later.
        store.settle(2 * SECOND + RequestTreeStore.RESOLVE_NANOS + RequestTreeStore.MAX_RESOLVE_EVERY_NANOS);
        store.settle(3 * SECOND + RequestTreeStore.RESOLVE_NANOS + RequestTreeStore.MAX_RESOLVE_EVERY_NANOS);

        assertThat(announced).containsExactly(RequestOutcome.UNKNOWN_ROUTE, RequestOutcome.UNKNOWN_ROUTE);
        assertThat(store.neverNamed()).isEqualTo(1L);
        assertThat(store.exemplars(RequestOutcome.UNKNOWN_ROUTE)).hasSize(2);
    }

    /**
     * I1 (M52-05): a tree waiting for its exchange keeps every fragment's nodes until its calls are attached, so a call
     * of its executor's fragment, or of its own fragments past the ones a settled tree keeps, still finds its node.
     */
    @Test
    void aWaitingTreeStillPlacesItsExecutorsCallsAndThoseOfItsOwnFragmentsPastTheKeptOnes() {
        String id = CodePathFragment.hex(1L);
        Map<String, RequestOutcome> outcomes = new HashMap<>();
        RequestTreeStore store = new RequestTreeStore(named(outcomes));
        int own = RequestTreeBuilder.MAX_KEPT_HOLDERS + 6;
        for (int sequence = 1; sequence <= own; sequence++) {
            store.add(
                    Blobs.request(1L, 1L)
                            .between(sequence * 100L, sequence * 100L + 10L)
                            .sequence(sequence)
                            .node(-1, 1, 2, 1L, 10L, 0L)
                            .fragment(),
                    0L);
        }
        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 50L)
                        .sequence(500L)
                        .submitter(CodePathStamps.pack(1L, 0, 1))
                        .node(-1, 9, 0, 1L, 50L, 0L)
                        .fragment(),
                0L);
        store.settle(2 * SECOND);
        assertThat(store.unresolvedCount()).isEqualTo(1);

        outcomes.put(
                id,
                new RequestOutcome(
                        "GET /stream",
                        200,
                        false,
                        List.of(
                                new RequestOutcome.StampedCall(CodePathStamps.SQL, CodePathStamps.pack(own, 0, 1), 5L),
                                new RequestOutcome.StampedCall(
                                        CodePathStamps.SQL, CodePathStamps.pack(500L, 0, 9), 7L)),
                        UnplacedCalls.NONE));
        store.settle(2 * SECOND + RequestTreeStore.RESOLVE_EVERY_NANOS);

        assertThat(store.unresolvedCount()).isZero();
        RequestTree tree = store.tree(id);
        assertThat(tree.method()).containsExactly(RequestTree.REQUEST, 1, RequestTree.ASYNC, 9);
        assertThat(tree.ioCalls(1, CodePathStamps.SQL))
                .as("the last own fragment's")
                .isEqualTo(1L);
        assertThat(tree.ioCalls(3, CodePathStamps.SQL)).as("the executor's").isEqualTo(1L);
        assertThat(tree.unplacedCalls()).isZero();
    }

    /** M3: a waiting tree that left the recent ones still takes its late fragments: no duplicate tree opens. */
    @Test
    void aLateFragmentOfAWaitingTreeNoLongerRecentMergesIntoIt() {
        String id = CodePathFragment.hex(1L);
        List<String> announced = new ArrayList<>();
        RequestTreeStore store = new RequestTreeStore(ids -> Map.of());
        store.onSettled((tree, outcome) -> announced.add(tree.key()));
        store.add(fragment(1L, 100L), 0L);
        store.settle(2 * SECOND);
        assertThat(store.unresolvedCount()).isEqualTo(1);
        // Work only executions own needs no exchange: enough of it pushes the waiting tree out of the recent ones.
        for (int execution = 1; execution <= RequestTreeStore.RECENT; execution++) {
            store.add(
                    Blobs.handoff(1L, 0L, 0x1000L + execution)
                            .between(0L, 5L)
                            .node(-1, 9, 0, 1L, 5L, 0L)
                            .fragment(),
                    2 * SECOND);
        }
        store.settle(4 * SECOND);
        assertThat(store.recent()).extracting(RequestTree::key).doesNotContain(id);

        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 50L)
                        .node(-1, 9, 0, 1L, 50L, 0L)
                        .fragment(),
                4 * SECOND);

        assertThat(store.openCount()).as("no partial duplicate opens").isZero();
        assertThat(store.late()).isEqualTo(1L);
        assertThat(store.tree(id).fragments()).isEqualTo(2);
        store.settleAll();
        assertThat(announced).filteredOn(id::equals).as("handed over once").hasSize(1);
    }

    /** M2: a stamped call whose fragment arrives after its tree settled is counted as late, not as missing. */
    @Test
    void aStampedCallWhoseFragmentArrivesLateIsCountedAsLate() {
        String id = CodePathFragment.hex(1L);
        RequestTreeStore store = new RequestTreeStore(ids -> Map.of(
                id,
                new RequestOutcome(
                        "GET /a",
                        200,
                        false,
                        List.of(new RequestOutcome.StampedCall(CodePathStamps.SQL, CodePathStamps.pack(99L, 0, 9), 7L)),
                        UnplacedCalls.NONE)));
        store.add(
                Blobs.request(1L, 1L)
                        .between(0L, 100L)
                        .sequence(1L)
                        .node(-1, 1, 2, 1L, 100L, 0L)
                        .fragment(),
                0L);
        store.settle(2 * SECOND);
        assertThat(store.tree(id).unplaced().missing()).isEqualTo(1L);

        store.add(
                Blobs.handoff(1L, 1L, 0xc1L)
                        .between(0L, 50L)
                        .sequence(99L)
                        .node(-1, 9, 0, 1L, 50L, 0L)
                        .fragment(),
                3 * SECOND);

        UnplacedCalls unplaced = store.tree(id).unplaced();
        assertThat(unplaced.missing()).isZero();
        assertThat(unplaced.late()).isEqualTo(1L);
    }

    /**
     * M4: while trees wait for their exchange, each look reads only what the journal recorded since the last one; the
     * journal is read in full again only for a tree whose exchange arrived.
     */
    @Test
    void waitingTreesAreLookedUpIncrementallyAndReadInFullOnlyOnceNamed() {
        String id = CodePathFragment.hex(1L);
        java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong(10L);
        Map<String, Long> exchanges = new HashMap<>();
        List<Long> looks = new ArrayList<>();
        int[] fullReads = new int[1];
        RequestTreeStore store = new RequestTreeStore(new RequestOutcomeReader() {
            @Override
            public Map<String, RequestOutcome> apply(Set<String> ids) {
                fullReads[0]++;
                Map<String, RequestOutcome> named = new HashMap<>();
                for (String requested : ids) {
                    if (exchanges.containsKey(requested)) {
                        named.put(requested, new RequestOutcome("GET /stream", 200, false));
                    }
                }
                return named;
            }

            @Override
            public long watermark() {
                return sequence.get();
            }

            @Override
            public Exchanges exchangesAfter(Set<String> ids, long after) {
                looks.add(after);
                Set<String> named = new java.util.HashSet<>();
                exchanges.forEach((requested, at) -> {
                    if (ids.contains(requested) && at > after) {
                        named.add(requested);
                    }
                });
                return new Exchanges(named, sequence.get());
            }
        });
        store.add(fragment(1L, 100L), 0L);
        store.settle(2 * SECOND);
        assertThat(fullReads[0]).isEqualTo(1);

        long now = 2 * SECOND;
        for (int poll = 0; poll < 3; poll++) {
            sequence.addAndGet(5L);
            now += RequestTreeStore.RESOLVE_EVERY_NANOS;
            store.settle(now);
        }
        assertThat(fullReads[0]).as("waiting costs no full read").isEqualTo(1);
        // The first look happens as it settles, from the watermark taken before its full read.
        assertThat(looks).containsExactly(10L, 10L, 15L, 20L);

        exchanges.put(id, sequence.incrementAndGet());
        store.settle(now + RequestTreeStore.RESOLVE_EVERY_NANOS);

        assertThat(fullReads[0]).as("one full read once named").isEqualTo(2);
        assertThat(store.unresolvedCount()).isZero();
        assertThat(store.outcome(id).route()).isEqualTo("GET /stream");
    }

    private static Map<String, RequestOutcome> everyRequest(String route, Set<String> ids) {
        Map<String, RequestOutcome> named = new HashMap<>();
        for (String id : ids) {
            named.put(id, new RequestOutcome(route, 200, false));
        }
        return named;
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
