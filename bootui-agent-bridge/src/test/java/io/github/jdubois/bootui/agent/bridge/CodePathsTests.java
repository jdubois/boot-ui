package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The code-paths sensor's bridge side (PLAN-v2 M5-4a), driven as the inlined advice would drive it: one
 * {@code enter} at each instrumented entry and one {@code exit} with its token in a {@code finally}.
 */
class CodePathsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();
    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicReference<Error> captureError = new AtomicReference<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void aCallChainUnderARequestIsOneFragment() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int b = id("B");
        int c = id("C");

        call(a, () -> {
            call(b, () -> call(c, null));
            call(b, () -> call(c, null));
            call(c, null);
        });

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(1);
        long[] blob = blobs.get(0);
        assertThat(blob[CodePaths.H_VERSION]).isEqualTo(CodePaths.BLOB_VERSION);
        assertThat(blob[CodePaths.H_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(blob[CodePaths.H_EXECUTION]).isZero();
        assertThat(blob[CodePaths.H_GENERATION]).isEqualTo(generation());
        assertThat(blob[CodePaths.H_END_NANOS]).isGreaterThanOrEqualTo(blob[CodePaths.H_START_NANOS]);
        // A, A>B, A>B>C, A>C
        assertThat(nodes(blob)).containsExactly("-1:A:1", "0:B:2", "1:C:2", "0:C:1");
        assertInvariants(blob);
        assertThat(captures).hasValue(1);
        assertThat(CodePaths.depth()).isZero();
        assertThat(CodePaths.recordingFragment()).isFalse();
    }

    @Test
    void enterAndExitBalanceUnderExceptions() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int b = id("B");

        call(a, () -> {
            try {
                call(b, () -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // The handler catches it, as an application would.
            }
            assertThat(CodePaths.depth()).isEqualTo(1);
            call(b, null);
        });

        assertThat(CodePaths.depth()).isZero();
        long[] blob = drain(token).get(0);
        assertThat(nodes(blob)).containsExactly("-1:A:1", "0:B:2");
        assertInvariants(blob);
    }

    @Test
    void aMissingExitIsToleratedByTheNextExit() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int outer = CodePaths.enter(id("A"));
        CodePaths.enter(id("B"));
        // B's exit never runs, as when its class was retransformed while it was open: A's exit pops both.
        CodePaths.exit(outer);

        assertThat(CodePaths.depth()).isZero();
        long[] blob = drain(token).get(0);
        assertThat(nodes(blob)).containsExactly("-1:A:1", "0:B:1");
        assertInvariants(blob);
        // An exit already popped is ignored.
        CodePaths.exit(outer);
        assertThat(CodePaths.depth()).isZero();
    }

    @Test
    void theNoOpTokenChangesNothing() {
        long token = claim();
        context.set(owner(REQUEST, null));
        CodePaths.exit(0);
        assertThat(CodePaths.depth()).isZero();

        int a = CodePaths.enter(id("A"));
        CodePaths.exit(0);
        assertThat(CodePaths.depth()).isEqualTo(1);
        CodePaths.exit(a);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void nothingIsRecordedWithoutAClaimForTheSensor() {
        long inventoryOnly = claim(List.of("inventory"), null);
        context.set(owner(REQUEST, null));
        // The inventory sensor warms the capture once at its claim.
        int warmed = captures.get();

        assertThat(CodePaths.enter(id("A"))).isZero();
        assertThat(CodePaths.depth()).isZero();
        assertThat(captures).hasValue(warmed);
        assertThat(drain(inventoryOnly)).isEmpty();
    }

    @Test
    void withoutAnOwnerOnlyDepthIsCountedAndTheCaptureRunsOncePerOutermostCall() {
        long token = claim();
        context.set(null);
        int a = id("A");
        int b = id("B");

        call(a, () -> {
            call(b, () -> call(b, null));
            assertThat(CodePaths.depth()).isEqualTo(1);
        });
        call(a, null);

        assertThat(drain(token)).isEmpty();
        assertThat(captures).hasValue(2);
        assertThat(CodePaths.status()).containsEntry("unowned", 2L);
    }

    @Test
    void anExecutionIdIsParsedExactly() {
        long token = claim();
        int a = id("A");
        context.set(owner(REQUEST, "async-00000000000000cd"));
        call(a, null);
        context.set(owner(REQUEST, "task-00000000000000ef"));
        call(a, null);
        context.set(owner(REQUEST, "async-not-hex-at-all!"));
        call(a, null);
        context.set(owner(null, "scheduled-1"));
        call(a, null);

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(3);
        assertThat(blobs.get(0)[CodePaths.H_EXECUTION]).isEqualTo(0xcdL);
        assertThat(blobs.get(0)[CodePaths.H_FLAGS] & 15).isEqualTo(CodePaths.EXECUTION_ASYNC);
        assertThat(blobs.get(1)[CodePaths.H_EXECUTION]).isEqualTo(0xefL);
        assertThat(blobs.get(1)[CodePaths.H_FLAGS] & 15).isEqualTo(CodePaths.EXECUTION_TASK);
        assertThat(blobs.get(2)[CodePaths.H_EXECUTION]).isZero();
        assertThat(blobs.get(2)[CodePaths.H_FLAGS] & 15).isEqualTo(CodePaths.EXECUTION_NONE);
    }

    @Test
    void anExclusionToggledMidCallStaysBalancedAndMovesLaterCallsToTheCaller() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int getter = id("getter");
        int c = id("C");

        call(a, () -> {
            int open = CodePaths.enter(getter);
            assertThat(CodePaths.exclude(token, getter)).isTrue();
            CodePaths.exit(open);
            assertThat(CodePaths.depth()).isEqualTo(1);
            // Excluded now: no token, and its callee attaches to its caller.
            call(getter, () -> call(c, null));
        });

        long[] blob = drain(token).get(0);
        assertThat(nodes(blob)).containsExactly("-1:A:1", "0:getter:1", "0:C:1");
        assertThat(CodePaths.excluded()).containsExactly(getter);
        assertThat(CodePaths.status()).containsEntry("excludedMethods", 1);
        assertThat(CodePaths.exclude(token + 1, c)).isFalse();
    }

    @Test
    void aNewRunForgetsExclusions() {
        long token = claim();
        int getter = id("getter");
        CodePaths.exclude(token, getter);
        assertThat(CodePaths.EXCLUDED[getter]).isEqualTo((byte) 1);

        claim();

        assertThat(CodePaths.EXCLUDED[getter]).isZero();
        assertThat(CodePaths.excluded()).isEmpty();
    }

    @Test
    void callsDeeperThanThirtyTwoLevelsStayInTheLevelThirtyTwoNode() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int[] chain = new int[40];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = id("M" + i);
        }

        nest(chain, 0);

        long[] blob = drain(token).get(0);
        assertThat(blob[CodePaths.H_NODES]).isEqualTo(CodePaths.MAX_DEPTH);
        assertThat(blob[CodePaths.H_DROPPED]).isEqualTo(chain.length - CodePaths.MAX_DEPTH);
        int deepest = CodePaths.MAX_DEPTH - 1;
        assertThat(node(blob, deepest, CodePaths.N_METHOD)).isEqualTo(chain[deepest]);
        assertThat(node(blob, deepest, CodePaths.N_CHILD)).isZero();
        assertInvariants(blob);
    }

    @Test
    void pastTheNodeBudgetEachParentGetsOneOtherNode() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int root = id("root");
        int[] children = new int[600];
        for (int i = 0; i < children.length; i++) {
            children[i] = id("child" + i);
        }
        int grandChild = id("grandChild");
        int extra = id("extra");

        call(root, () -> {
            for (int i = 0; i < children.length; i++) {
                call(children[i], i >= 590 ? () -> call(grandChild, null) : null);
            }
            call(children[0], () -> call(extra, null));
        });

        long[] blob = drain(token).get(0);
        int regular = CodePaths.MAX_NODES - CodePaths.OTHER_RESERVE;
        // root, the children until the regular budget ends, then the root's Other and the first child's Other.
        assertThat(blob[CodePaths.H_NODES]).isEqualTo(regular + 2);
        Map<Long, Long> otherCalls = new LinkedHashMap<>();
        for (int node = 0; node < blob[CodePaths.H_NODES]; node++) {
            if (node(blob, node, CodePaths.N_METHOD) == CodePaths.OTHER) {
                otherCalls.put(node(blob, node, CodePaths.N_PARENT), node(blob, node, CodePaths.N_CALLS));
                assertThat(node(blob, node, CodePaths.N_CHILD))
                        .as("calls under Other stay in it")
                        .isZero();
            }
        }
        assertThat(otherCalls)
                .containsExactly(Map.entry(0L, (long) children.length - (regular - 1)), Map.entry(1L, 1L));
        // The grandchildren under the root's Other.
        assertThat(blob[CodePaths.H_DROPPED]).isEqualTo(10L);
        assertInvariants(blob);
    }

    @Test
    void anEmptyPoolDropsTheFragmentAndCountsIt() throws Exception {
        long token = claim(List.of(CodePaths.SENSOR), Map.of("poolSize", 1));
        context.set(owner(REQUEST, null));
        int a = id("A");
        CountDownLatch open = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        // The capture reads this test's context from any thread.
        Thread holder = new Thread(() -> {
            int held = CodePaths.enter(a);
            open.countDown();
            try {
                done.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            CodePaths.exit(held);
        });
        holder.start();
        open.await();

        call(a, null);
        assertThat(CodePaths.status()).containsEntry("fragmentsDropped", 1L);
        done.countDown();
        holder.join(5_000);

        assertThat(drain(token)).hasSize(1);
        call(a, null);
        assertThat(drain(token)).hasSize(1);
        assertThat(CodePaths.status()).containsEntry("poolCreated", 1).containsEntry("poolFree", 1);
    }

    @Test
    void aFullQueueDropsTheBlobAndCountsIt() {
        long token = claim(List.of(CodePaths.SENSOR), Map.of("queueBytes", CodePaths.MIN_QUEUE_BYTES));
        context.set(owner(REQUEST, null));
        int root = id("root");
        // 301 nodes: 14,544 bytes a blob, so four fit in 64 KB and a fifth does not.
        int[] children = new int[300];
        for (int i = 0; i < children.length; i++) {
            children[i] = id("c" + i);
        }
        Runnable wide = () -> call(root, () -> {
            for (int child : children) {
                call(child, null);
            }
        });

        for (int i = 0; i < 5; i++) {
            wide.run();
        }

        Map<String, Object> status = CodePaths.status();
        assertThat(status).containsEntry("queueDropped", 1L).containsEntry("fragmentsFlushed", 4L);
        assertThat((Long) status.get("queueBytes")).isLessThanOrEqualTo(CodePaths.MIN_QUEUE_BYTES);
        assertThat(drain(token)).hasSize(4);
        assertThat(CodePaths.status()).containsEntry("queueBytes", 0L).containsEntry("queued", 0);
    }

    @Test
    void internalErrorsResetTheFrameAndSwitchTheSensorOffAtTheLimit() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int open = CodePaths.enter(a);
        int epoch = CodePaths.epoch();

        // An id past the table: the bridge's own failure, caught.
        assertThat(CodePaths.enter(CodeInventory.MAX_METHODS)).isZero();
        assertThat(CodePaths.epoch()).isEqualTo(epoch + 1);
        assertThat(CodePaths.depth()).isZero();
        // The token of the frame's previous epoch is ignored.
        CodePaths.exit(open);
        assertThat(CodePaths.depth()).isZero();
        call(a, null);
        assertThat(drain(token)).hasSize(1);

        for (int i = 1; i < CodePaths.MAX_ERRORS; i++) {
            CodePaths.enter(-1);
        }
        Map<String, Object> status = CodePaths.status();
        assertThat(status).containsEntry("off", true).containsEntry("active", false);
        assertThat((String) status.get("offReason")).contains("100 internal errors");
        assertThat(CodePaths.enter(a)).isZero();
        claim();
        assertThat(CodePaths.enter(a)).as("off for the JVM's life").isZero();
    }

    @Test
    void aVirtualMachineErrorInsideEnterResetsTheFrameButNeverSwitchesTheSensorOff() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int epoch = CodePaths.epoch();

        // The capture runs inside enter: an application's runaway recursion overflows its stack there as anywhere.
        for (int i = 0; i < 2 * CodePaths.MAX_ERRORS; i++) {
            captureError.set(i % 2 == 0 ? new StackOverflowError() : new OutOfMemoryError("Java heap space"));
            assertThat(CodePaths.enter(a)).isZero();
            assertThat(CodePaths.depth()).isZero();
        }
        captureError.set(null);

        assertThat(CodePaths.epoch()).isEqualTo(epoch + 2 * CodePaths.MAX_ERRORS);
        assertThat(CodePaths.status())
                .containsEntry("off", false)
                .containsEntry("active", true)
                .containsEntry("errors", 0L)
                .containsEntry("applicationErrors", (long) (2 * CodePaths.MAX_ERRORS));
        call(a, null);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void anApplicationsRunawayRecursionThroughInstrumentedMethodsNeverSwitchesTheSensorOff() throws Exception {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        AtomicReference<Throwable> overflow = new AtomicReference<>();
        AtomicInteger depthAfter = new AtomicInteger(-1);
        for (int round = 0; round < 3; round++) {
            Thread thread = new Thread(
                    null,
                    () -> {
                        try {
                            recurse(a);
                        } catch (StackOverflowError expected) {
                            overflow.set(expected);
                        }
                        depthAfter.set(CodePaths.depth());
                    },
                    "recursion",
                    256 * 1024);
            thread.start();
            thread.join(30_000);
            assertThat(overflow.get()).isInstanceOf(StackOverflowError.class);
            assertThat(depthAfter).hasValue(0);
        }

        Map<String, Object> status = CodePaths.status();
        assertThat(status).containsEntry("off", false).containsEntry("errors", 0L);
        call(a, null);
        assertThat(drain(token)).isNotEmpty();
    }

    @Test
    void anExitOfAnEarlierEpochClosesACallWhoseExitWasLostSinceTheFrameReset() {
        long token = claim();
        int a = id("A");
        int b = id("B");
        // A is entered before its request is known, so it only counts depth.
        int outer = CodePaths.enter(a);
        assertThat(CodePaths.depth()).isEqualTo(1);
        // The request's scope opens inside A, and its capture overflows: the frame is reset, so A's token is stale.
        captureError.set(new StackOverflowError());
        CodePaths.begin();
        captureError.set(null);
        assertThat(CodePaths.depth()).isZero();
        // B is entered since the reset and its exit is lost, as when a second overflow escapes the entry that counted
        // it, before the frame could be reset again.
        context.set(owner(REQUEST, null));
        assertThat(CodePaths.enter(b)).isNotZero();
        assertThat(CodePaths.depth()).isEqualTo(1);
        CodePaths.end();

        CodePaths.exit(outer);

        assertThat(CodePaths.depth()).isZero();
        assertThat(CodePaths.recordingFragment()).isFalse();
        // The thread records again: B's abandoned fragment is dropped, not left open to swallow the next calls.
        call(a, null);
        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(1);
        assertThat(nodes(blobs.get(0))).containsExactly("-1:A:1");
        assertThat(CodePaths.status())
                .containsEntry("off", false)
                .containsEntry("errors", 0L)
                .containsEntry("applicationErrors", 1L)
                .containsEntry("abandonedFragments", 1L);
    }

    private static void recurse(int id) {
        int token = CodePaths.enter(id);
        try {
            recurse(id);
        } finally {
            CodePaths.exit(token);
        }
    }

    @Test
    void aStaleTokenDrainsNothingAndOlderBlobsGoToTheNextDrainer() {
        long first = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        call(a, null);
        long firstGeneration = generation();

        long second = claim();
        call(a, null);

        assertThat(drain(first)).isEmpty();
        assertThat(CodePaths.status()).containsEntry("staleDrains", 1L);
        List<long[]> blobs = drain(second);
        assertThat(blobs)
                .extracting(blob -> blob[CodePaths.H_GENERATION])
                .containsExactly(firstGeneration, generation());
    }

    @Test
    void aFragmentOpenAtDisarmStillFlushesAndReturnsItsTree() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int open = CodePaths.enter(a);
        AgentBridge.disarm(token);
        assertThat(CodePaths.enter(id("B"))).isZero();
        CodePaths.exit(open);

        assertThat(CodePaths.status()).containsEntry("fragmentsFlushed", 1L).containsEntry("poolFree", 1);
    }

    @Test
    void beginAndEndBoundAFragmentAndNodesRecordTheirPhase() {
        long token = claim();
        int filter = id("ApplicationFilter");
        int controller = id("Controller");
        int advice = id("ResponseAdvice");

        // An application filter outside BootUI's scope: no owner yet.
        call(filter, () -> {
            context.set(owner(REQUEST, null));
            CodePaths.begin();
            CodePaths.phase(CodePaths.PHASE_FILTERS);
            CodePaths.phase(CodePaths.PHASE_HANDLER);
            call(controller, null);
            CodePaths.phase(CodePaths.PHASE_RESPONSE);
            call(advice, null);
            CodePaths.end();
            context.set(null);
        });

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(1);
        long[] blob = blobs.get(0);
        assertThat(blob[CodePaths.H_FLAGS] & CodePaths.FLAG_BEGUN).isEqualTo(CodePaths.FLAG_BEGUN);
        assertThat(nodes(blob)).containsExactly("-1:Controller:1", "-1:ResponseAdvice:1");
        assertThat(node(blob, 0, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_HANDLER);
        assertThat(node(blob, 1, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        assertThat(CodePaths.depth()).isZero();
    }

    /**
     * M52-04: a method called under one parent in the handler and again in the response write is two nodes, each with
     * its own calls and time, whichever phase came first, so the response's work never lands on the handler's node.
     */
    @Test
    void theSameMethodInAnotherPhaseIsAnotherNode() {
        long token = claim();
        int helper = id("Helper");
        int inner = id("Inner");

        context.set(owner(REQUEST, null));
        CodePaths.begin();
        CodePaths.phase(CodePaths.PHASE_RESPONSE);
        call(helper, () -> call(inner, null));
        CodePaths.phase(CodePaths.PHASE_HANDLER);
        call(helper, () -> call(inner, null));
        call(helper, null);
        CodePaths.phase(CodePaths.PHASE_RESPONSE);
        call(helper, null);
        CodePaths.end();
        context.set(null);

        long[] blob = drain(token).get(0);
        assertThat(nodes(blob)).containsExactly("-1:Helper:2", "0:Inner:1", "-1:Helper:2", "2:Inner:1");
        assertThat(node(blob, 0, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        assertThat(node(blob, 1, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        assertThat(node(blob, 2, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_HANDLER);
        assertThat(node(blob, 3, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_HANDLER);
        assertInvariants(blob);
    }

    @Test
    void endClosesCallsStillOpenAndMarksTheFragmentCut() {
        long token = claim();
        context.set(owner(REQUEST, null));
        CodePaths.begin();
        int open = CodePaths.enter(id("Async"));
        CodePaths.end();

        long[] blob = drain(token).get(0);
        assertThat(blob[CodePaths.H_FLAGS] & CodePaths.FLAG_CUT).isEqualTo(CodePaths.FLAG_CUT);
        assertThat(nodes(blob)).containsExactly("-1:Async:1");
        CodePaths.exit(open);
        assertThat(CodePaths.depth()).isZero();
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void aBeginWhoseEndNeverCameIsFlushedCutAtTheNextBegin() {
        long token = claim();
        context.set(owner(REQUEST, null));
        CodePaths.begin();
        call(id("First"), null);
        // No end(): the next request on this pooled thread begins.
        context.set(owner("00000000000000cd", null));
        CodePaths.begin();
        call(id("Second"), null);
        CodePaths.end();

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(2);
        assertThat(blobs.get(0)[CodePaths.H_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(blobs.get(0)[CodePaths.H_FLAGS] & CodePaths.FLAG_CUT).isEqualTo(CodePaths.FLAG_CUT);
        assertThat(nodes(blobs.get(0))).containsExactly("-1:First:1");
        assertThat(blobs.get(1)[CodePaths.H_REQUEST]).isEqualTo(0xcdL);
        assertThat(nodes(blobs.get(1))).containsExactly("-1:Second:1");
        assertThat(CodePaths.status()).containsEntry("abandonedFragments", 1L);
    }

    @Test
    void aScopeInWhichNoBeanMethodRanIsNotFlushed() {
        long token = claim();
        context.set(owner(REQUEST, null));
        CodePaths.begin();
        CodePaths.phase(CodePaths.PHASE_HANDLER);
        CodePaths.end();

        assertThat(drain(token)).isEmpty();
        assertThat(CodePaths.status())
                .containsEntry("fragmentsFlushed", 0L)
                .containsEntry("poolCreated", 1)
                .containsEntry("poolFree", 1);
    }

    @Test
    void aBeginForTheSameRequestInsideItsScopeIsNestedAndIgnoredWithItsEnd() {
        long token = claim();
        context.set(owner(REQUEST, null));
        CodePaths.begin();
        call(id("Before"), null);
        // A Vert.x reroute inside rc.next(): the same request's scope opens again at the same depth.
        CodePaths.begin();
        call(id("Rerouted"), null);
        CodePaths.end();
        call(id("After"), null);
        // A forward from inside a handler: a call is open.
        int open = CodePaths.enter(id("Handler"));
        CodePaths.begin();
        CodePaths.end();
        CodePaths.exit(open);
        CodePaths.end();

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(1);
        assertThat(nodes(blobs.get(0))).containsExactly("-1:Before:1", "-1:Rerouted:1", "-1:After:1", "-1:Handler:1");
        assertThat(blobs.get(0)[CodePaths.H_FLAGS] & CodePaths.FLAG_CUT).isZero();
        assertThat(CodePaths.status()).containsEntry("abandonedFragments", 0L);
        assertThat(CodePaths.recordingFragment()).isFalse();
    }

    @Test
    void aFragmentNoBeginOpenedForgetsThePhaseWhenItFlushes() {
        long token = claim();
        context.set(owner(REQUEST, null));
        // A worker thread: the resource method's request filter marks the phase, with no begin().
        CodePaths.phase(CodePaths.PHASE_RESPONSE);
        call(id("Serializer"), null);
        // The worker's next work, owned by nothing BootUI scoped.
        call(id("Scheduled"), null);

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(2);
        assertThat(node(blobs.get(0), 0, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        assertThat(node(blobs.get(1), 0, CodePaths.N_PHASE)).isEqualTo(CodePaths.PHASE_UNKNOWN);
    }

    @Test
    void aNewGenerationFreesThePoolSlotsOfTreesItsThreadsNeverReturned() throws Exception {
        claim(List.of(CodePaths.SENSOR), Map.of("poolSize", 1));
        context.set(owner(REQUEST, null));
        int a = id("A");
        // A thread that dies inside a request's scope keeps the only tree of the pool.
        Thread dying = new Thread(CodePaths::begin);
        dying.start();
        dying.join(5_000);
        call(a, null);
        assertThat(CodePaths.status()).containsEntry("fragmentsDropped", 1L).containsEntry("poolCreated", 1);
        // This thread's own scope, opened before the next run, ends after it.
        CodePaths.begin();
        assertThat(CodePaths.status()).containsEntry("fragmentsDropped", 2L);

        long token = claim(List.of(CodePaths.SENSOR), Map.of("poolSize", 1));
        assertThat(CodePaths.status()).containsEntry("poolCreated", 0).containsEntry("poolFree", 0);
        call(a, null);
        assertThat(drain(token)).hasSize(1);
        assertThat(CodePaths.status()).containsEntry("poolCreated", 1).containsEntry("poolFree", 1);
    }

    @Test
    void aTreeOfAnEarlierGenerationIsDroppedWhenItComesBack() {
        claim();
        context.set(owner(REQUEST, null));
        CodePaths.begin();
        call(id("A"), null);
        assertThat(CodePaths.status()).containsEntry("poolCreated", 1).containsEntry("poolFree", 0);

        long token = claim();
        assertThat(CodePaths.status()).containsEntry("poolCreated", 0);
        CodePaths.end();
        assertThat(CodePaths.status()).containsEntry("poolCreated", 0).containsEntry("poolFree", 0);
        // Its fragment was still queued for the next drainer, as the earlier generation's.
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void aStampNamesTheInnermostOpenNodeOfTheFragment() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        int b = id("B");
        int c = id("C");
        List<Long> stamps = new ArrayList<>();

        assertThat(CodePaths.stamp()).isZero();
        call(a, () -> {
            stamps.add(CodePaths.stamp());
            call(b, () -> {
                stamps.add(CodePaths.stamp());
                call(c, () -> stamps.add(CodePaths.stamp()));
                stamps.add(CodePaths.stamp());
            });
            call(b, () -> stamps.add(CodePaths.stamp()));
        });
        assertThat(CodePaths.stamp()).isZero();

        long[] blob = drain(token).get(0);
        long sequence = blob[CodePaths.H_SEQUENCE];
        assertThat(sequence).isPositive();
        assertThat(blob[CodePaths.H_SUBMITTER]).isZero();
        assertThat(nodes(blob)).containsExactly("-1:A:1", "0:B:2", "1:C:1");
        assertThat(stamps)
                .allSatisfy(stamp -> assertThat(CodePaths.stampSequence(stamp)).isEqualTo(sequence));
        assertThat(stamps.stream().map(CodePaths::stampNode).toList()).containsExactly(0, 1, 2, 1, 1);
        assertThat(stamps.stream().map(CodePaths::stampMethod).toList()).containsExactly(a, b, c, b, b);
    }

    @Test
    void eachFragmentTakesTheNextSequence() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        long[] stamps = new long[2];

        call(a, () -> stamps[0] = CodePaths.stamp());
        call(a, () -> stamps[1] = CodePaths.stamp());

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(2);
        assertThat(blobs.get(1)[CodePaths.H_SEQUENCE]).isEqualTo(blobs.get(0)[CodePaths.H_SEQUENCE] + 1);
        assertThat(CodePaths.stampSequence(stamps[0])).isEqualTo(blobs.get(0)[CodePaths.H_SEQUENCE]);
        assertThat(CodePaths.stampSequence(stamps[1])).isEqualTo(blobs.get(1)[CodePaths.H_SEQUENCE]);
    }

    @Test
    void aCallPastTheDepthCapStampsTheDeepestRecordedNode() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int[] chain = new int[CodePaths.MAX_DEPTH + 3];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = id("D" + i);
        }
        long[] deepest = new long[1];

        nestThen(chain, 0, () -> deepest[0] = CodePaths.stamp());

        long[] blob = drain(token).get(0);
        assertThat(CodePaths.stampNode(deepest[0])).isEqualTo(CodePaths.MAX_DEPTH - 1);
        assertThat(CodePaths.stampMethod(deepest[0])).isEqualTo(chain[CodePaths.MAX_DEPTH - 1]);
        assertThat(CodePaths.stampSequence(deepest[0])).isEqualTo(blob[CodePaths.H_SEQUENCE]);
    }

    @Test
    void anOtherNodeStampsWithoutAMethod() {
        assertThat(CodePaths.stampMethod(CodePaths.pack(7L, 3, CodePaths.OTHER)))
                .isEqualTo(CodePaths.OTHER);
        assertThat(CodePaths.stampNode(CodePaths.pack(7L, 511, 5))).isEqualTo(511);
        long largest = CodePaths.pack(CodePaths.MAX_SEQUENCE, 511, CodeInventory.MAX_METHODS - 1);
        assertThat(largest).isPositive();
        assertThat(CodePaths.stampSequence(largest)).isEqualTo(CodePaths.MAX_SEQUENCE);
        assertThat(CodePaths.stampMethod(largest)).isEqualTo(CodeInventory.MAX_METHODS - 1);
    }

    @Test
    void anotherThreadStampsNothing() throws Exception {
        claim();
        context.set(owner(REQUEST, null));
        AtomicReference<Long> other = new AtomicReference<>();
        call(id("A"), () -> {
            Thread thread = new Thread(() -> other.set(CodePaths.stamp()));
            thread.start();
            try {
                thread.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            assertThat(CodePaths.stamp()).isNotZero();
        });
        assertThat(other.get()).isZero();
    }

    @Test
    void aStampIsZeroWhileTheSensorIsOffOrWithoutAnOwner() {
        int a = id("A");
        long[] stamp = new long[1];
        call(a, () -> stamp[0] = CodePaths.stamp());
        assertThat(stamp[0]).isZero();

        claim(List.of("inventory"), null);
        context.set(owner(REQUEST, null));
        call(a, () -> stamp[0] = CodePaths.stamp());
        assertThat(stamp[0]).isZero();

        claim();
        context.set(null);
        call(a, () -> stamp[0] = CodePaths.stamp());
        assertThat(stamp[0]).isZero();
    }

    @Test
    void theSequenceWrapsToOneAndNeverStampsZero() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        long[] stamps = new long[2];
        CodePaths.sequence(CodePaths.MAX_SEQUENCE - 1);

        call(a, () -> stamps[0] = CodePaths.stamp());
        call(a, () -> stamps[1] = CodePaths.stamp());

        List<long[]> blobs = drain(token);
        assertThat(blobs.get(0)[CodePaths.H_SEQUENCE]).isEqualTo(CodePaths.MAX_SEQUENCE);
        assertThat(blobs.get(1)[CodePaths.H_SEQUENCE]).isEqualTo(1L);
        assertThat(CodePaths.stampSequence(stamps[0])).isEqualTo(CodePaths.MAX_SEQUENCE);
        assertThat(stamps[0]).isPositive();
        assertThat(CodePaths.stampSequence(stamps[1])).isEqualTo(1L);
        assertThat(stamps[1]).isNotZero();
    }

    @Test
    void aHandoffFragmentRecordsItsSubmitterUntilTheWorkEnds() {
        long token = claim();
        int a = id("A");
        long submitter = CodePaths.pack(42L, 3, a);

        context.set(owner(REQUEST, "async-00000000000000cd"));
        CodePaths.handoff(submitter);
        call(a, null);
        CodePaths.handoffDone();
        call(a, null);
        // The request's own work never records a submitter, even when one is set.
        context.set(owner(REQUEST, null));
        CodePaths.handoff(submitter);
        call(a, null);
        CodePaths.handoffDone();

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(3);
        assertThat(blobs.get(0)[CodePaths.H_SUBMITTER]).isEqualTo(submitter);
        assertThat(blobs.get(1)[CodePaths.H_SUBMITTER]).isZero();
        assertThat(blobs.get(2)[CodePaths.H_SUBMITTER]).isZero();
    }

    @Test
    void workAnExecutorRanOnTheSubmittingThreadRestoresTheOuterSubmitter() {
        long token = claim();
        int a = id("A");
        long outer = CodePaths.pack(42L, 3, a);
        long inner = CodePaths.pack(43L, 1, a);
        context.set(owner(REQUEST, "async-00000000000000cd"));

        CodePaths.handoff(outer);
        call(a, null);
        // A caller-runs or direct executor runs nested work on this thread, inside the outer work.
        CodePaths.handoff(inner);
        call(a, null);
        CodePaths.handoffDone();
        call(a, null);
        CodePaths.handoffDone();
        call(a, null);
        // An unmatched end never leaves a stale submitter.
        CodePaths.handoffDone();
        call(a, null);

        List<long[]> blobs = drain(token);
        assertThat(blobs).hasSize(5);
        assertThat(blobs.stream().map(blob -> blob[CodePaths.H_SUBMITTER]).toList())
                .containsExactly(outer, inner, outer, 0L, 0L);
    }

    @Test
    void nestedHandoffsPastTheBoundEndWithoutASubmitter() {
        long token = claim();
        int a = id("A");
        context.set(owner(REQUEST, "async-00000000000000cd"));
        for (int i = 1; i <= CodePaths.MAX_HANDOFFS + 1; i++) {
            CodePaths.handoff(CodePaths.pack(i, 0, a));
        }
        CodePaths.handoffDone();
        call(a, null);
        for (int i = 0; i < CodePaths.MAX_HANDOFFS; i++) {
            CodePaths.handoffDone();
        }
        call(a, null);

        List<long[]> blobs = drain(token);
        // Past the bound, the replaced submitter is not kept; within it, every one is restored.
        assertThat(blobs.get(0)[CodePaths.H_SUBMITTER]).isZero();
        assertThat(blobs.get(1)[CodePaths.H_SUBMITTER]).isZero();
    }

    /**
     * I5: a transaction interceptor commits after the {@code @Transactional} method returned, so a statement Hibernate
     * flushes at commit is stamped to the method that called it, not to the transactional method.
     */
    @Test
    void aStatementFlushedAtCommitIsStampedToTheCallerOfTheTransactionalMethod() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int controller = id("Controller");
        int transactional = id("Service");
        long[] stamps = new long[2];

        call(controller, () -> {
            // The interceptor proceeds into the target, which issues its own statements there...
            call(transactional, () -> stamps[0] = CodePaths.stamp());
            // ...then commits once it returned: the flush runs with only the caller open.
            stamps[1] = CodePaths.stamp();
        });

        drain(token);
        assertThat(CodePaths.stampMethod(stamps[0])).isEqualTo(transactional);
        assertThat(CodePaths.stampMethod(stamps[1])).isEqualTo(controller);
    }

    @Test
    void aCallOutsideEveryInstrumentedMethodOfAnOpenFragmentStampsOutside() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int a = id("A");
        long[] stamps = new long[3];

        CodePaths.begin();
        // A filter, before the handler: the begun fragment is open, no instrumented call is.
        stamps[0] = CodePaths.stamp();
        call(a, () -> stamps[1] = CodePaths.stamp());
        // The response write, or a commit after the outermost instrumented method returned.
        stamps[2] = CodePaths.stamp();
        CodePaths.end();

        assertThat(stamps[0]).isEqualTo(CodePaths.STAMP_OUTSIDE);
        assertThat(stamps[1]).isPositive();
        assertThat(stamps[2]).isEqualTo(CodePaths.STAMP_OUTSIDE);
        // Without a fragment on the thread, as on another thread, the stamp is 0, not outside.
        assertThat(CodePaths.stamp()).isZero();
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void theSelfTestThreadCountsDepthWhileInactive() {
        int a = id("A");
        assertThat(CodePaths.enter(a)).isZero();
        CodePaths.beginSelfTest();
        try {
            int token = CodePaths.enter(a);
            assertThat(CodePaths.depth()).isEqualTo(1);
            CodePaths.exit(token);
            assertThat(CodePaths.depth()).isZero();
        } finally {
            CodePaths.endSelfTest();
        }
    }

    /**
     * Seeded random programs of nested calls, some throwing, some excluded, deep and wide enough to hit both caps,
     * against a straightforward recursive reference of the merge, the depth cap, and the Other bucket.
     */
    @Test
    void randomProgramsMatchTheReference() {
        for (long seed = 1; seed <= 300; seed++) {
            AgentBridge.reset();
            AgentBridge.install(request -> Map.of("status", "ok"));
            long token = claim();
            context.set(owner(REQUEST, null));
            Random random = new Random(seed);
            int methods = 2 + random.nextInt(seed % 3 == 0 ? 400 : 12);
            int[] ids = new int[methods];
            for (int i = 0; i < methods; i++) {
                ids[i] = CodeInventory.methodId("com.example.R#m" + i + "()V");
            }
            boolean[] excluded = new boolean[methods];
            for (int i = 1; i < methods; i++) {
                if (random.nextInt(10) == 0) {
                    excluded[i] = true;
                    CodePaths.exclude(token, ids[i]);
                }
            }
            Program program = Program.random(random, methods, 0, seed % 5 == 0 ? 45 : 12, new int[] {0});

            run(program, ids);

            List<long[]> blobs = drain(token);
            assertThat(blobs).as("seed %d", seed).hasSize(1);
            long[] blob = blobs.get(0);
            Reference reference = new Reference();
            reference.visit(program, excluded, -1, 1, false);
            List<String> actual = new ArrayList<>();
            int count = (int) blob[CodePaths.H_NODES];
            for (int node = 0; node < count; node++) {
                long method = node(blob, node, CodePaths.N_METHOD);
                int index = method == CodePaths.OTHER ? -1 : indexOf(ids, (int) method);
                actual.add(
                        node(blob, node, CodePaths.N_PARENT) + ":" + index + ":" + node(blob, node, CodePaths.N_CALLS));
            }
            assertThat(actual).as("seed %d", seed).containsExactlyElementsOf(reference.nodes());
            assertThat(blob[CodePaths.H_DROPPED]).as("seed %d", seed).isEqualTo(reference.dropped);
            assertInvariants(blob);
            assertThat(CodePaths.depth()).isZero();
        }
    }

    // ---- the random programs and their reference
    // ---------------------------------------------------------------------

    /** A call of method index {@code method}, its callees, and whether it throws after them. */
    record Program(int method, List<Program> callees, boolean throwsAtEnd) {

        static Program random(Random random, int methods, int depth, int maxDepth, int[] budget) {
            List<Program> callees = new ArrayList<>();
            if (depth < maxDepth) {
                int count = depth == 0 ? 1 + random.nextInt(6) : random.nextInt(depth > 6 ? 2 : 5);
                for (int i = 0; i < count && budget[0] < 3_000; i++) {
                    budget[0]++;
                    callees.add(random(random, methods, depth + 1, maxDepth, budget));
                }
            }
            return new Program(depth == 0 ? 0 : random.nextInt(methods), callees, depth > 0 && random.nextInt(8) == 0);
        }
    }

    private static void run(Program program, int[] ids) {
        int token = CodePaths.enter(ids[program.method()]);
        try {
            for (Program callee : program.callees()) {
                try {
                    run(callee, ids);
                } catch (IllegalStateException expected) {
                    // Caught by the caller, which goes on.
                }
            }
            if (program.throwsAtEnd()) {
                throw new IllegalStateException("thrown");
            }
        } finally {
            CodePaths.exit(token);
        }
    }

    /** The reference: path-keyed maps and recursion, with the bridge's caps and Other rule. */
    static final class Reference {

        final List<int[]> nodes = new ArrayList<>();
        final List<Long> calls = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        long dropped;

        void visit(Program program, boolean[] excluded, int parent, int level, boolean collapsed) {
            if (excluded[program.method()]) {
                for (Program callee : program.callees()) {
                    visit(callee, excluded, parent, level, collapsed);
                }
                return;
            }
            if (collapsed || level > CodePaths.MAX_DEPTH) {
                dropped++;
                for (Program callee : program.callees()) {
                    visit(callee, excluded, parent, level + 1, true);
                }
                return;
            }
            int node = node(parent, program.method());
            if (node < 0) {
                dropped++;
                for (Program callee : program.callees()) {
                    visit(callee, excluded, parent, level + 1, true);
                }
                return;
            }
            calls.set(node, calls.get(node) + 1);
            boolean other = nodes.get(node)[1] == -1;
            for (Program callee : program.callees()) {
                visit(callee, excluded, node, level + 1, other);
            }
        }

        int node(int parent, int method) {
            Integer known = index.get(parent + "/" + method);
            if (known != null) {
                return known;
            }
            if (nodes.size() < CodePaths.MAX_NODES - CodePaths.OTHER_RESERVE) {
                return add(parent, method);
            }
            Integer other = index.get(parent + "/-1");
            if (other != null) {
                return other;
            }
            return nodes.size() < CodePaths.MAX_NODES ? add(parent, -1) : -1;
        }

        int add(int parent, int method) {
            nodes.add(new int[] {parent, method});
            calls.add(0L);
            index.put(parent + "/" + method, nodes.size() - 1);
            return nodes.size() - 1;
        }

        List<String> nodes() {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                list.add(nodes.get(i)[0] + ":" + nodes.get(i)[1] + ":" + calls.get(i));
            }
            return list;
        }
    }

    // ---- helpers
    // -----------------------------------------------------------------------------------------------------

    private static int indexOf(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -2;
    }

    private static void nest(int[] chain, int at) {
        if (at == chain.length) {
            return;
        }
        call(chain[at], () -> nest(chain, at + 1));
    }

    private static void nestThen(int[] chain, int at, Runnable innermost) {
        if (at == chain.length) {
            innermost.run();
            return;
        }
        call(chain[at], () -> nestThen(chain, at + 1, innermost));
    }

    private static void call(int id, Runnable body) {
        int token = CodePaths.enter(id);
        try {
            if (body != null) {
                body.run();
            }
        } finally {
            CodePaths.exit(token);
        }
    }

    private static int id(String name) {
        return CodeInventory.methodId("com.example.Shop#" + name + "()V");
    }

    private static String name(long id) {
        if (id == CodePaths.OTHER) {
            return "Other";
        }
        String key = CodeInventory.methodKeys((int) id, 1)[0];
        return key.substring(key.indexOf('#') + 1, key.indexOf('('));
    }

    private static long node(long[] blob, int node, int field) {
        return blob[CodePaths.HEADER + node * CodePaths.NODE + field];
    }

    /** {@code parent:method:calls} per node, in node order. */
    private static List<String> nodes(long[] blob) {
        List<String> list = new ArrayList<>();
        for (int node = 0; node < blob[CodePaths.H_NODES]; node++) {
            list.add(node(blob, node, CodePaths.N_PARENT) + ":" + name(node(blob, node, CodePaths.N_METHOD)) + ":"
                    + node(blob, node, CodePaths.N_CALLS));
        }
        return list;
    }

    /** Times are never negative, children's time never exceeds the parent's, and parents precede children. */
    private static void assertInvariants(long[] blob) {
        int count = (int) blob[CodePaths.H_NODES];
        long[] childrenTotal = new long[count];
        for (int node = 0; node < count; node++) {
            long parent = node(blob, node, CodePaths.N_PARENT);
            assertThat(parent).isLessThan(node);
            assertThat(node(blob, node, CodePaths.N_TOTAL)).isNotNegative();
            assertThat(node(blob, node, CodePaths.N_CHILD)).isBetween(0L, node(blob, node, CodePaths.N_TOTAL));
            if (parent >= 0) {
                childrenTotal[(int) parent] += node(blob, node, CodePaths.N_TOTAL);
            }
        }
        for (int node = 0; node < count; node++) {
            assertThat(childrenTotal[node]).isEqualTo(node(blob, node, CodePaths.N_CHILD));
        }
    }

    private static Object[] owner(String request, String execution) {
        return new Object[] {request, execution, null, null, "/shop", null, null, 1L, 1L};
    }

    private long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private long claim() {
        return claim(List.of(CodePaths.SENSOR), null);
    }

    private long claim(List<String> sensors, Map<String, Object> options) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        if (options != null) {
            request.put("codePaths", options);
        }
        Supplier<Object> capture = () -> {
            captures.incrementAndGet();
            Error error = captureError.get();
            if (error != null) {
                throw error;
            }
            return context.get();
        };
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    private static List<long[]> drain(long token) {
        List<long[]> blobs = new ArrayList<>();
        CodePaths.drain(token, blobs::add);
        return blobs;
    }
}
