package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.core.dto.CodePathsCallsDto;
import io.github.jdubois.bootui.core.dto.CodePathsNodeDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Call-site stamps in the engine ({@code docs/PLAN-v2.md} §5.14, M5-4c): read from the journal, attached under the exact
 * request-tree node that issued them, summed per route-tree node, subtracted from its own time, and a handoff's
 * fragment placed under the node that submitted it.
 */
class CodePathStampsTests {

    private static final long REQUEST = 0xabL;
    private static final String REQUEST_ID = "00000000000000ab";
    private static final long MS = 1_000_000L;

    @Test
    void theEngineUnpacksStampsExactlyAsTheBridgePacksThem() {
        for (long sequence : new long[] {1L, 42L, CodePaths.MAX_SEQUENCE}) {
            for (int node : new int[] {0, 7, CodePaths.MAX_NODES - 1}) {
                for (int method : new int[] {CodePaths.OTHER, 0, 12_345, CodeInventory.MAX_METHODS - 1}) {
                    long stamp = CodePaths.pack(sequence, node, method);
                    assertThat(CodePathStamps.pack(sequence, node, method)).isEqualTo(stamp);
                    assertThat(CodePathStamps.sequence(stamp)).isEqualTo(sequence);
                    assertThat(CodePathStamps.node(stamp)).isEqualTo(node);
                    assertThat(CodePathStamps.method(stamp)).isEqualTo(method);
                }
            }
        }
        assertThat(CodePathStamps.label("shop.OwnerService#findAll()Ljava/util/List;"))
                .isEqualTo("OwnerService.findAll");
        assertThat(CodePathStamps.label("#12")).isEqualTo("#12");
    }

    @Test
    void aFragmentOfVersionOneStillDecodesWithoutASequence() {
        Blobs blobs = Blobs.request(1L, REQUEST).between(0L, 10L).sequence(9L).node(-1, 3, 2, 1L, 10L, 0L);

        CodePathFragment current = CodePathFragment.decode(blobs.blob());
        CodePathFragment older = CodePathFragment.decode(blobs.blobVersion1());

        assertThat(current.sequence()).isEqualTo(9L);
        assertThat(older).isNotNull();
        assertThat(older.sequence()).isZero();
        assertThat(older.submitter()).isZero();
        assertThat(older.method()).containsExactly(3);
    }

    @Test
    void stampedCallsAttachUnderTheNodeThatIssuedThemAndLeaveItsOwnTime() {
        RequestTreeBuilder builder = new RequestTreeBuilder(REQUEST_ID, REQUEST_ID);
        builder.add(Blobs.request(1L, REQUEST)
                .between(0L, 100 * MS)
                .sequence(5L)
                .node(-1, 1, 2, 1L, 100 * MS, 80 * MS)
                .node(0, 2, 2, 1L, 80 * MS, 0L)
                .fragment());
        builder.attach(
                List.of(
                        call(CodePathStamps.SQL, CodePathStamps.pack(5L, 1, 2), 20 * MS),
                        call(CodePathStamps.SQL, CodePathStamps.pack(5L, 1, 2), 30 * MS),
                        call(CodePathStamps.REST, CodePathStamps.pack(5L, 0, 1), 5 * MS),
                        call(CodePathStamps.CACHE, CodePathStamps.pack(5L, 1, 2), -1L),
                        call(CodePathStamps.SQL, CodePathStamps.pack(6L, 0, 1), 1 * MS)),
                2);
        RequestTree tree = builder.finish();

        assertThat(tree.method()).containsExactly(RequestTree.REQUEST, 1, 2);
        assertThat(tree.ioCalls(2, CodePathStamps.SQL)).isEqualTo(2);
        assertThat(tree.ioNanos(2, CodePathStamps.SQL)).isEqualTo(50 * MS);
        assertThat(tree.ioCalls(2, CodePathStamps.CACHE)).isEqualTo(1);
        assertThat(tree.ioCalls(1, CodePathStamps.REST)).isEqualTo(1);
        assertThat(tree.ownNanos(2)).isEqualTo(30 * MS);
        assertThat(tree.ownNanos(1)).isEqualTo(15 * MS);
        assertThat(tree.unplacedCalls())
                .as("a stamp of a fragment the tree does not hold")
                .isEqualTo(1);
        assertThat(tree.unstampedCalls()).isEqualTo(2);

        List<CodePathsNodeDto> nodes = CodePathsViews.nodes(tree, id -> "shop.S#m" + id + "()V");
        assertThat(nodes.get(2).calls())
                .containsExactly(new CodePathsCallsDto("SQL", 2.0, 50.0), new CodePathsCallsDto("CACHE", 1.0, null));
        assertThat(nodes.get(1).calls()).containsExactly(new CodePathsCallsDto("REST", 1.0, 5.0));
    }

    @Test
    void aCallUnderAnOtherNodeOrAFoldedOneAttachesWhereItsTimeIs() {
        RequestTreeBuilder builder = new RequestTreeBuilder("k", null);
        Blobs blobs = Blobs.request(1L, REQUEST).between(0L, 1_000L).sequence(3L);
        // A fragment Other node, merged into the request tree's Other node under A.
        blobs.node(-1, 1, 2, 1L, 1_000L, 500L).node(0, CodePathFragment.OTHER, 2, 4L, 500L, 0L);
        builder.add(blobs.fragment());
        builder.attach(List.of(call(CodePathStamps.SQL, CodePathStamps.pack(3L, 1, -1), 100L)), 0);
        RequestTree tree = builder.finish();

        assertThat(tree.method()[2]).isEqualTo(RequestTree.OTHER);
        assertThat(tree.ioCalls(2, CodePathStamps.SQL)).isEqualTo(1);
    }

    /**
     * Design I7: a handoff's fragment carries the stamp of the node that submitted it, and goes under that node, kept
     * apart and never subtracted, even when it arrives before the submitting thread's fragment; one whose submitter's
     * fragment never arrives goes under the request.
     */
    @Test
    void aHandoffGoesUnderTheNodeThatSubmittedItWhateverTheOrderItArrives() {
        RequestTreeBuilder builder = new RequestTreeBuilder(REQUEST_ID, REQUEST_ID);
        builder.add(Blobs.handoff(1L, REQUEST, 0xc1L)
                .between(10L, 60L)
                .sequence(8L)
                .submitter(CodePathStamps.pack(7L, 1, 2))
                .node(-1, 9, 0, 1L, 50L, 0L)
                .fragment());
        builder.add(Blobs.handoff(1L, REQUEST, 0xc2L)
                .between(10L, 30L)
                .sequence(9L)
                .submitter(CodePathStamps.pack(70L, 0, 1))
                .node(-1, 9, 0, 1L, 20L, 0L)
                .fragment());
        assertThat(builder.build().nodeCount())
                .as("both wait for their submitter")
                .isEqualTo(1);

        builder.add(Blobs.request(1L, REQUEST)
                .between(0L, 100L)
                .sequence(7L)
                .node(-1, 1, 2, 1L, 100L, 40L)
                .node(0, 2, 2, 1L, 40L, 0L)
                .fragment());
        RequestTree tree = builder.finish();

        // REQUEST, A, B, ASYNC c1 under B, its method, then ASYNC c2 under the request, its method.
        assertThat(tree.method())
                .containsExactly(RequestTree.REQUEST, 1, 2, RequestTree.ASYNC, 9, RequestTree.ASYNC, 9);
        assertThat(tree.parent()).containsExactly(-1, 0, 1, 2, 3, 0, 5);
        assertThat(tree.execution()[3]).isEqualTo(0xc1L);
        assertThat(tree.execution()[5]).isEqualTo(0xc2L);
        assertThat(tree.selfNanos(2)).as("never subtracted").isEqualTo(40L);
        assertThat(tree.asyncNanos()).isEqualTo(50L);

        RouteTrees routes = new RouteTrees();
        routes.add(tree, "GET /a", false);
        routes.add(tree, "GET /a", false);
        RouteTree route = routes.route("GET /a");
        int asyncNode = -1;
        for (int node = 0; node < route.nodeCount(); node++) {
            if (route.method(node) == RequestTree.ASYNC && route.method(route.parent(node)) == 2) {
                asyncNode = node;
            }
        }
        assertThat(asyncNode).as("the route keeps it under B").isPositive();
        assertThat(route.async(asyncNode)).isTrue();
        assertThat(route.handlerNanos()).isEqualTo(60L + 40L);
    }

    @Test
    void aLateHandoffStillFindsItsSubmitterAfterTheTreeSettled() {
        RequestTreeStore store = new RequestTreeStore(ids -> Map.of());
        store.add(
                Blobs.request(1L, REQUEST)
                        .between(0L, 100L)
                        .sequence(7L)
                        .node(-1, 1, 2, 1L, 100L, 0L)
                        .fragment(),
                0L);
        store.settle(RequestTreeStore.SETTLE_NANOS);
        store.add(
                Blobs.handoff(1L, REQUEST, 0xc1L)
                        .between(200L, 300L)
                        .sequence(8L)
                        .submitter(CodePathStamps.pack(7L, 0, 1))
                        .node(-1, 9, 0, 1L, 100L, 0L)
                        .fragment(),
                RequestTreeStore.SETTLE_NANOS + 1);

        RequestTree tree = store.tree(REQUEST_ID);
        assertThat(tree.method()).containsExactly(RequestTree.REQUEST, 1, RequestTree.ASYNC, 9);
        assertThat(tree.parent()).containsExactly(-1, 0, 1, 2);
    }

    @Test
    void routeTreesSumTheCallsPerNodeAndTheHandlerSplitsByOwnTime() {
        RouteTrees routes = new RouteTrees();
        for (int request = 0; request < 3; request++) {
            RequestTreeBuilder builder = new RequestTreeBuilder("k" + request, String.format("%016x", request));
            builder.add(Blobs.request(1L, request + 1L)
                    .between(0L, 100 * MS)
                    .sequence(10L + request)
                    .node(-1, 1, 2, 1L, 100 * MS, 90 * MS)
                    .node(0, 2, 2, 1L, 90 * MS, 0L)
                    .fragment());
            builder.attach(
                    List.of(
                            call(CodePathStamps.SQL, CodePathStamps.pack(10L + request, 1, 2), 30 * MS),
                            call(CodePathStamps.AI, CodePathStamps.pack(10L + request, 1, 2), 40 * MS)),
                    request == 2 ? 1 : 0);
            routes.add(builder.finish(), "GET /quote", false);
        }
        RouteTree route = routes.route("GET /quote");

        assertThat(route.warmRequests()).isEqualTo(2);
        assertThat(route.ioCalls(2, CodePathStamps.SQL)).isEqualTo(2);
        assertThat(route.ioNanos(2)).isEqualTo(2 * 70 * MS);
        assertThat(route.ownNanos(2)).isEqualTo(2 * 20 * MS);
        assertThat(route.handlerOwnNanos()).isEqualTo(2 * 30 * MS);
        assertThat(route.unstampedCalls()).isEqualTo(1);
        CodePathsNodeDto slow =
                CodePathsViews.node(route, 2, id -> "shop.S#m" + id + "()V", CodePathsViews.childCounts(route));
        assertThat(slow.calls())
                .containsExactly(new CodePathsCallsDto("SQL", 1.0, 30.0), new CodePathsCallsDto("AI", 1.0, 40.0));
        HandlerMethods handler = CodePathsViews.handler(route, id -> "shop.S#m" + id + "()V");
        assertThat(handler.handlerNanos()).isEqualTo(2 * 30 * MS);
        assertThat(handler.methods().get(0).label()).isEqualTo("S.m2");
        assertThat(handler.methods().get(0).ownNanos()).isEqualTo(2 * 20 * MS);
        assertThat(handler.stampedCalls()).isEqualTo(4);
        assertThat(handler.unstampedCalls()).isEqualTo(1);
    }

    /**
     * M52-04: one method under one parent in the handler and the response write is two nodes in the request tree, across
     * fragments, and in the route tree, whichever phase each request reached first; the handler split counts only the
     * handler's.
     */
    @Test
    void aMethodInTwoPhasesStaysTwoNodesAndOnlyItsHandlerTimeIsSplit() {
        RequestTreeBuilder single = new RequestTreeBuilder("k", REQUEST_ID);
        single.add(Blobs.request(1L, REQUEST)
                .between(0L, 10L)
                .node(-1, 1, 2, 1L, 10L, 0L)
                .fragment());
        single.add(Blobs.request(1L, REQUEST)
                .between(20L, 25L)
                .node(-1, 1, 3, 1L, 5L, 0L)
                .fragment());
        single.add(Blobs.request(1L, REQUEST)
                .between(30L, 34L)
                .node(-1, 1, 2, 1L, 4L, 0L)
                .fragment());
        RequestTree merged = single.finish();
        assertThat(merged.method()).containsExactly(RequestTree.REQUEST, 1, 1);
        assertThat(merged.phase()).containsExactly((byte) 0, (byte) 2, (byte) 3);
        assertThat(merged.calls()).containsExactly(3L, 2L, 1L);
        assertThat(merged.total()).containsExactly(19L, 14L, 5L);

        RouteTrees routes = new RouteTrees();
        for (int request = 0; request < 3; request++) {
            Blobs blobs = Blobs.request(1L, request + 1L).between(0L, 50 * MS);
            if (request % 2 == 0) {
                blobs.node(-1, 1, 3, 1L, 10 * MS, 0L).node(-1, 1, 2, 1L, 40 * MS, 0L);
            } else {
                blobs.node(-1, 1, 2, 1L, 40 * MS, 0L).node(-1, 1, 3, 1L, 10 * MS, 0L);
            }
            RequestTreeBuilder builder = new RequestTreeBuilder("k" + request, String.format("%016x", request + 1));
            builder.add(blobs.fragment());
            routes.add(builder.finish(), "GET /a", false);
        }
        RouteTree route = routes.route("GET /a");
        assertThat(route.nodeCount()).isEqualTo(3);
        for (int node = 1; node < 3; node++) {
            assertThat(route.selfNanos(node))
                    .as("node %d, phase %d", node, route.phase(node))
                    .isEqualTo(route.phase(node) == 2 ? 80 * MS : 20 * MS);
        }
        assertThat(route.handlerNanos()).isEqualTo(80 * MS);
        HandlerMethods handler = CodePathsViews.handler(route, id -> "shop.S#m" + id + "()V");
        assertThat(handler.handlerNanos()).isEqualTo(80 * MS);
        assertThat(handler.methods())
                .singleElement()
                .satisfies(method -> assertThat(method.ownNanos()).isEqualTo(80 * MS));
    }

    /** M52-03: labels stay short unless two methods share one, then gain their parameters, then their package. */
    @Test
    void labelsAreDistinctPerMethodKeyAndShortWhereTheyCanBe() {
        assertThat(CodePathStamps.labels(List.of(
                        "shop.Service#work(Ljava/lang/String;)V",
                        "shop.Service#work(I[J)V",
                        "a.Util#run()V",
                        "b.Util#run()V",
                        "shop.Quote#price()I",
                        "shop.Quote#price()I")))
                .containsExactly(
                        "Service.work(String)",
                        "Service.work(int, long[])",
                        "a.Util.run()",
                        "b.Util.run()",
                        "Quote.price",
                        "Quote.price");
        assertThat(CodePathStamps.labels(List.of("#7", "#8"))).containsExactly("#7", "#8");
    }

    @Test
    void theJournalNamesEachRequestsStampedCallsAndCountsTheRest() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        try {
            CorrelationContext request = CorrelationContext.forRequest(REQUEST_ID);
            CorrelationContext other = CorrelationContext.forRequest("00000000000000cd");
            long stamp = CodePathStamps.pack(4L, 1, 2);
            journal.offer(event(JournalSource.SQL, 5_000L, 3 * MS, request, sql(stamp)));
            journal.offer(event(JournalSource.SQL, 5_001L, 2 * MS, request, sql(0L)));
            journal.offer(event(
                    JournalSource.REST_CLIENT,
                    5_002L,
                    4 * MS,
                    request,
                    new RestClientPayload("GET", "api:443", "/x", 200, "RestClient", false, null, -1, stamp)));
            journal.offer(
                    event(JournalSource.CACHE, 5_003L, -1, request, new CachePayload("quotes", "HIT", null, stamp)));
            journal.offer(event(
                    JournalSource.AI,
                    5_004L,
                    9 * MS,
                    request,
                    new AiPayload("chat", "openai", "gpt", 1L, 2L, "stop", false, null, -1, stamp)));
            journal.offer(event(JournalSource.SQL, 5_005L, 1 * MS, other, sql(stamp)));
            journal.offer(event(
                    JournalSource.HTTP,
                    5_000L,
                    20 * MS,
                    request,
                    new HttpPayload("GET", "/quote", "/quote", null, 200, null, null)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            Map<String, RequestOutcome> outcomes =
                    JournalRequestOutcomes.of(journal, null).apply(Set.of(REQUEST_ID));

            RequestOutcome outcome = outcomes.get(REQUEST_ID);
            assertThat(outcome.route()).isEqualTo("GET /quote");
            assertThat(outcome.unstampedCalls()).isEqualTo(1);
            assertThat(outcome.calls())
                    .extracting(RequestOutcome.StampedCall::kind)
                    .containsExactlyInAnyOrder(
                            CodePathStamps.SQL, CodePathStamps.REST, CodePathStamps.CACHE, CodePathStamps.AI);
            assertThat(outcome.calls()).allMatch(call -> call.stamp() == stamp);
            assertThat(outcome.withoutCalls().calls()).isEmpty();
            assertThat(outcomes).doesNotContainKey("00000000000000cd");
        } finally {
            journal.close();
        }
    }

    /**
     * B1: events are appended as they complete, so a long call of another request, which started well before this
     * request but completed between its calls and its exchange, never stops the read before this request's calls.
     */
    @Test
    void aLongEventBetweenARequestsExchangeAndItsCallsNeverHidesThem() throws Exception {
        RuntimeJournal journal = journal(10_000);
        try {
            CorrelationContext request = CorrelationContext.forRequest(REQUEST_ID);
            CorrelationContext other = CorrelationContext.forRequest("00000000000000cd");
            long stamp = CodePathStamps.pack(4L, 1, 2);
            // In completion order: this request's statement, another request's 3.5 s statement, this exchange.
            journal.offer(event(JournalSource.SQL, 5_005L, 1 * MS, request, sql(stamp)));
            journal.offer(event(JournalSource.SQL, 1_000L, 3_500 * MS, other, sql(0L)));
            journal.offer(event(
                    JournalSource.HTTP,
                    5_000L,
                    20 * MS,
                    request,
                    new HttpPayload("GET", "/quote", "/quote", null, 200, null, null)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            RequestOutcome outcome = JournalRequestOutcomes.of(journal, null)
                    .apply(Set.of(REQUEST_ID))
                    .get(REQUEST_ID);

            assertThat(outcome.route()).isEqualTo("GET /quote");
            assertThat(outcome.calls())
                    .as("the read goes past the long event, which ended after the request started")
                    .singleElement()
                    .satisfies(call -> assertThat(call.stamp()).isEqualTo(stamp));
        } finally {
            journal.close();
        }
    }

    /**
     * I2: a call issued while the request's fragment was open but no instrumented method was, as in a filter or the
     * response write, is counted apart from one recorded on a thread without a fragment, and each says why.
     */
    @Test
    void callsOutsideEveryMethodAreCountedApartFromCallsOnAnotherThread() throws Exception {
        RuntimeJournal journal = journal(10_000);
        try {
            CorrelationContext request = CorrelationContext.forRequest(REQUEST_ID);
            journal.offer(event(JournalSource.SQL, 5_001L, 3 * MS, request, sql(CodePathStamps.OUTSIDE)));
            journal.offer(event(JournalSource.SQL, 5_002L, 2 * MS, request, sql(0L)));
            journal.offer(event(
                    JournalSource.HTTP,
                    5_000L,
                    20 * MS,
                    request,
                    new HttpPayload("GET", "/quote", "/quote", null, 200, null, null)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

            RequestOutcome outcome = JournalRequestOutcomes.of(journal, null)
                    .apply(Set.of(REQUEST_ID))
                    .get(REQUEST_ID);

            assertThat(outcome.calls()).isEmpty();
            assertThat(outcome.unplaced()).isEqualTo(new UnplacedCalls(1L, 2 * MS, 1L, 0L, 0L, 0L));
            RequestTreeBuilder builder = new RequestTreeBuilder(REQUEST_ID, REQUEST_ID);
            builder.add(Blobs.request(1L, REQUEST)
                    .between(0L, 10L)
                    .sequence(4L)
                    .node(-1, 1, 2, 1L, 10L, 0L)
                    .fragment());
            builder.attach(outcome.calls(), outcome.unplaced());
            RequestTree tree = builder.finish();
            assertThat(tree.unstampedCalls()).isEqualTo(1);
            assertThat(tree.unplaced().outside()).isEqualTo(1);
            assertThat(tree.unplacedCalls())
                    .as("neither names a missing fragment")
                    .isZero();
        } finally {
            journal.close();
        }

        List<String> limitations =
                CodePathsService.unstampedLimitations(new UnplacedCalls(1L, 0L, 2L, 3L, 4L, 5L), "request");
        assertThat(limitations).hasSize(5);
        assertThat(limitations.get(0))
                .startsWith("1 recorded call of the request carried no stamp: it was recorded on a thread where")
                .contains("another thread")
                .doesNotContain("self time of the method that waited");
        assertThat(limitations.get(1))
                .startsWith("2 recorded calls of the request ran on the request's thread while no instrumented method"
                        + " was open")
                .contains("filter", "response was written", "commit")
                .doesNotContain("another thread");
        assertThat(limitations.get(2)).startsWith("3 stamped calls of the request named a fragment the tree did not");
        assertThat(limitations.get(3))
                .startsWith("4 stamped calls of the request ran in a fragment that arrived after");
        assertThat(limitations.get(4)).startsWith("5 stamped calls of the request came past the 10,000");
    }

    /** M2: stamped calls past the bound a request keeps are counted as such, never silently lost. */
    @Test
    void stampedCallsPastTheBoundAreCountedAsUnplaced() throws Exception {
        // A queue for every offer, none reserved: a dispatcher lagging behind the burst must not drop routine events.
        int events = RequestOutcome.MAX_CALLS + 100;
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, events, 200_000_000L, 2 * events, 10, 0, JournalSource.all()),
                RunIdentity.start());
        try {
            CorrelationContext request = CorrelationContext.forRequest(REQUEST_ID);
            long stamp = CodePathStamps.pack(4L, 0, 1);
            for (int i = 0; i < RequestOutcome.MAX_CALLS + 3; i++) {
                journal.offer(event(JournalSource.SQL, 5_001L, 1_000L, request, sql(stamp)));
            }
            journal.offer(event(
                    JournalSource.HTTP,
                    5_000L,
                    20 * MS,
                    request,
                    new HttpPayload("GET", "/quote", "/quote", null, 200, null, null)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();

            RequestOutcome outcome = JournalRequestOutcomes.of(journal, null)
                    .apply(Set.of(REQUEST_ID))
                    .get(REQUEST_ID);

            assertThat(outcome.calls()).hasSize(RequestOutcome.MAX_CALLS);
            assertThat(outcome.unplaced().overflow()).isEqualTo(3);
            RequestTreeBuilder builder = new RequestTreeBuilder(REQUEST_ID, REQUEST_ID);
            builder.add(Blobs.request(1L, REQUEST)
                    .between(0L, 10L)
                    .sequence(4L)
                    .node(-1, 1, 2, 1L, 10L, 0L)
                    .fragment());
            builder.attach(outcome.calls(), outcome.unplaced());
            RequestTree tree = builder.finish();
            assertThat(tree.ioCalls(1, CodePathStamps.SQL)).isEqualTo(RequestOutcome.MAX_CALLS);
            assertThat(tree.unplacedCalls()).isEqualTo(3);
        } finally {
            journal.close();
        }
    }

    /**
     * M4: a tree waiting for its exchange is looked up in the events recorded since the last look only, never in a copy
     * of the whole journal.
     */
    @Test
    void theJournalTellsWhichExchangesArrivedSinceAWatermark() throws Exception {
        RuntimeJournal journal = journal(10_000);
        try {
            CorrelationContext request = CorrelationContext.forRequest(REQUEST_ID);
            CorrelationContext other = CorrelationContext.forRequest("00000000000000cd");
            journal.offer(event(
                    JournalSource.HTTP, 1_000L, MS, other, new HttpPayload("GET", "/a", "/a", null, 200, null, null)));
            journal.offer(event(JournalSource.SQL, 1_001L, MS, request, sql(0L)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            RequestOutcomeReader reader = JournalRequestOutcomes.of(journal, null);
            long watermark = reader.watermark();
            assertThat(watermark).isEqualTo(2L);

            RequestOutcomeReader.Exchanges nothing = reader.exchangesAfter(Set.of(REQUEST_ID), watermark);
            assertThat(nothing.named()).isEmpty();
            assertThat(nothing.watermark()).isEqualTo(watermark);
            assertThat(journal.entriesAfter(watermark)).isEmpty();

            journal.offer(event(
                    JournalSource.HTTP,
                    1_000L,
                    5 * MS,
                    request,
                    new HttpPayload("GET", "/quote", "/quote", null, 200, null, null)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            assertThat(journal.entriesAfter(watermark)).hasSize(1);
            RequestOutcomeReader.Exchanges arrived = reader.exchangesAfter(Set.of(REQUEST_ID), watermark);
            assertThat(arrived.named()).containsExactly(REQUEST_ID);
            assertThat(arrived.watermark()).isEqualTo(3L);
            assertThat(reader.exchangesAfter(Set.of(REQUEST_ID), arrived.watermark())
                            .named())
                    .as("read once")
                    .isEmpty();
        } finally {
            journal.close();
        }
    }

    private static RuntimeJournal journal(int events) {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, events, 200_000_000L, events, 10, 10, JournalSource.all()),
                RunIdentity.start());
    }

    private static SqlPayload sql(long stamp) {
        return new SqlPayload("select * from owner", null, "db", false, null, null, -1, stamp);
    }

    private static RuntimeEvent event(
            JournalSource source,
            long startMillis,
            long nanos,
            CorrelationContext context,
            io.github.jdubois.bootui.engine.journal.RuntimeEventPayload payload) {
        return RuntimeEvent.of(source, startMillis, nanos, context, "http-1", null, false, payload);
    }

    private static RequestOutcome.StampedCall call(int kind, long stamp, long nanos) {
        return new RequestOutcome.StampedCall(kind, stamp, nanos);
    }
}
