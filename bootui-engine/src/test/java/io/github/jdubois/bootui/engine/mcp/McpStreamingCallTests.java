package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import io.github.jdubois.bootui.engine.progress.ProgressPhase;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class McpStreamingCallTests {

    private static final ProgressPhase PHASE = ProgressPhase.of("Working");
    private static final McpProgressToken TOKEN = McpProgressToken.of("t-1");

    private final AtomicInteger reported = new AtomicInteger();
    private final AllowAll policy = new AllowAll();

    @Test
    void progressIsStreamedInOrderThenExactlyOneFinalResponse() throws Exception {
        McpDispatcher dispatcher = dispatcher(args -> {
            for (int i = 1; i <= 3; i++) {
                OperationProgress.current().report(PHASE, i, 3);
            }
            return "done";
        });
        RecordingSink sink = new RecordingSink();

        McpStreamingCall call = stream(dispatcher.start(request(TOKEN), true));
        call.start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages)
                .containsExactly(
                        "progress t-1 1.0/3.0 Working",
                        "progress t-1 2.0/3.0 Working",
                        "progress t-1 3.0/3.0 Working",
                        "complete " + new ToolCallResult("done"),
                        "close");
        assertAllPermitsFree(dispatcher, 2);
        assertThat(dispatcher.runtimeStats().snapshot().callCount()).isEqualTo(1);
    }

    @Test
    void onlyModernProgressCallsToProgressToolsFromStreamingClientsAreStreamed() {
        McpDispatcher dispatcher = dispatcher(args -> "done");
        McpRequest legacy = new McpRequest(
                "2.0", "tools/call", false, null, "architecture_scan", null, null, null, Set.of(), null, null, null);
        assertThat(dispatcher.start(legacy, true)).isEqualTo(new McpCallStart.Immediate(new ToolCallResult("done")));
        assertThat(dispatcher.start(request(null), true)).isInstanceOf(McpCallStart.Immediate.class);
        assertThat(dispatcher.start(request(TOKEN), false)).isInstanceOf(McpCallStart.Immediate.class);

        McpDispatcher plain = new McpDispatcher(
                List.of(new McpTool(
                        "get_overview",
                        "Overview.",
                        McpToolSchema.NONE,
                        BootUiPanels.OVERVIEW,
                        false,
                        a -> "overview")),
                List.of(),
                policy,
                "1.0",
                "x",
                50,
                2,
                30_000,
                (operation, failure) -> reported.incrementAndGet());
        McpRequest overview = new McpRequest(
                "2.0",
                "tools/call",
                false,
                null,
                "get_overview",
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.MODERN,
                TOKEN);
        assertThat(plain.start(overview, true))
                .as("a tool without measured phases never streams")
                .isEqualTo(new McpCallStart.Immediate(new ToolCallResult("overview")));
    }

    @Test
    void refusalsAndCapacityAreImmediateAndHoldNoPermit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    await(release);
                    return "done";
                },
                1,
                30_000);
        policy.disabled = true;
        assertThat(dispatcher.start(request(TOKEN), true))
                .extracting(start -> ((McpCallStart.Immediate) start).outcome())
                .isInstanceOf(ToolCallError.class);
        assertAllPermitsFree(dispatcher, 1);
        policy.disabled = false;

        RecordingSink first = new RecordingSink();
        stream(dispatcher.start(request(TOKEN), true)).start(first);
        assertThat(dispatcher.start(request(McpProgressToken.of(2)), true))
                .isEqualTo(new McpCallStart.Immediate(
                        new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE)));
        release.countDown();
        assertThat(first.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertAllPermitsFree(dispatcher, 1);
        assertThat(dispatcher.runtimeStats().snapshot().capacityRefusals()).isEqualTo(1);
    }

    @Test
    void cancellingInterruptsTheToolWritesNothingMoreAndFreesThePermitWhenTheToolReturns() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(args -> {
            try {
                OperationProgress.current().report(PHASE, 1, 0);
                running.countDown();
                Thread.sleep(30_000);
                return "never";
            } catch (InterruptedException interrupted) {
                OperationProgress.current().report(PHASE, 2, 0);
                return "interrupted";
            } finally {
                exited.countDown();
            }
        });
        RecordingSink sink = new RecordingSink();
        McpStreamingCall call = stream(dispatcher.start(request(TOKEN), true));
        call.start(sink);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        call.cancel();
        call.cancel();

        assertThat(exited.await(5, TimeUnit.SECONDS))
                .as("the tool thread is interrupted")
                .isTrue();
        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages).noneMatch(message -> message.startsWith("complete"));
        assertThat(sink.messages).doesNotContain("progress t-1 2.0 Working");
        assertThat(sink.messages).last().isEqualTo("close");
        assertAllPermitsFree(dispatcher, 2);
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.cancellations()).isEqualTo(1);
        assertThat(stats.timeouts()).isZero();
        assertThat(stats.callCount()).isEqualTo(1);
        assertThat(reported).hasValue(0);
    }

    @Test
    void theAbsoluteTimeoutStillEndsAStreamThatKeepsReportingProgress() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    try {
                        for (int i = 1; ; i++) {
                            OperationProgress.current().report(PHASE, i, 0);
                            OperationProgress.current().checkCancelled();
                            Thread.sleep(5);
                        }
                    } catch (InterruptedException interrupted) {
                        return "interrupted";
                    } finally {
                        exited.countDown();
                    }
                },
                2,
                300);
        RecordingSink sink = new RecordingSink();
        long startedAt = System.nanoTime();
        stream(dispatcher.start(request(TOKEN), true)).start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages.get(sink.messages.size() - 2))
                .isEqualTo("complete " + new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE));
        // The burst, one event per interval of the measured run, and the newest one flushed before the final response.
        assertThat(sink.messages.stream().filter(message -> message.startsWith("progress")))
                .as("progress is rate-limited: a burst of 8, then one every 250 ms")
                .hasSizeBetween(
                        2, McpProgressThrottle.BURST + 2 + (int) (elapsedMillis / McpProgressThrottle.INTERVAL_MILLIS));
        assertAllPermitsFree(dispatcher, 2);
        assertThat(dispatcher.runtimeStats().snapshot().timeouts()).isEqualTo(1);
        assertThat(dispatcher.runtimeStats().snapshot().cancellations()).isZero();
        assertThat(reported)
                .as("a timed-out tool's cancellation is not a server fault")
                .hasValue(0);
    }

    @Test
    void aCallTheAdapterNeverStartsIsReleasedByCancelOrByTheTimeout() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    invocations.incrementAndGet();
                    return "done";
                },
                2,
                200);

        McpStreamingCall cancelled = stream(dispatcher.start(request(TOKEN), true));
        assertThat(availablePermits(dispatcher)).isEqualTo(1);
        cancelled.cancel();
        assertThat(availablePermits(dispatcher)).isEqualTo(2);

        stream(dispatcher.start(request(TOKEN), true));
        assertThat(availablePermits(dispatcher)).isEqualTo(1);
        assertAllPermitsFree(dispatcher, 2);
        assertThat(invocations).hasValue(0);
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.cancellations()).isEqualTo(1);
        assertThat(stats.timeouts()).isEqualTo(1);
        assertThat(stats.callCount()).isEqualTo(2);
    }

    @Test
    void aServerFaultWhileWritingIsReportedAsAFaultNotCountedAsACancellation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    OperationProgress.current().report(PHASE, 1, 0);
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "done";
                },
                1,
                30_000);
        RecordingSink sink = new RecordingSink() {
            @Override
            public void progress(McpProgressToken token, ProgressEvent event) {
                throw new IllegalArgumentException("An MCP stream event must be one line of compact JSON");
            }
        };
        stream(dispatcher.start(request(TOKEN), true)).start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertAllPermitsFree(dispatcher, 1);
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.cancellations())
                .as("a rendering fault is not the client's cancellation")
                .isZero();
        assertThat(stats.timeouts()).isZero();
        assertThat(reported).as("the fault is reported").hasValue(1);
        assertThat(sink.messages).containsExactly("close");
    }

    @Test
    void aCallThatTimedOutBeforeItsStreamStartedStillAnswersWithItsFinalResponse() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    invocations.incrementAndGet();
                    return "done";
                },
                1,
                50);
        McpStreamingCall call = stream(dispatcher.start(request(TOKEN), true));
        assertAllPermitsFree(dispatcher, 1);

        RecordingSink sink = new RecordingSink();
        call.start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages)
                .as("a 200 stream is never left without its final response")
                .containsExactly(
                        "complete " + new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE),
                        "close");
        assertThat(invocations).hasValue(0);
        assertThat(dispatcher.runtimeStats().snapshot().timeouts()).isEqualTo(1);

        RecordingSink second = new RecordingSink();
        call.start(second);
        assertThat(second.messages).as("a second start only closes its sink").containsExactly("close");
    }

    @Test
    void aCallCancelledBeforeItsStreamStartedClosesWithoutAResponse() throws Exception {
        McpDispatcher dispatcher = dispatcher(args -> "done", 1, 30_000);
        McpStreamingCall call = stream(dispatcher.start(request(TOKEN), true));
        call.cancel();

        RecordingSink sink = new RecordingSink();
        call.start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages).containsExactly("close");
    }

    @Test
    void streamFramesAreOneLineOfJson() {
        assertThat(McpProtocol.sseDataFrame("{\"a\":1}")).isEqualTo("data:{\"a\":1}\n\n");
        assertThat(McpProtocol.sseData("{\"a\":1}")).isEqualTo("{\"a\":1}");
        for (String broken : List.of("{\n\"a\":1}", "{\"a\":1}\r", "{\r\n}")) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> McpProtocol.sseDataFrame(broken));
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> McpProtocol.sseData(broken));
        }
    }

    @Test
    void aBrokenClientConnectionCancelsTheCall() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(args -> {
            try {
                OperationProgress.current().report(PHASE, 1, 0);
                Thread.sleep(30_000);
                return "never";
            } catch (InterruptedException interrupted) {
                return "interrupted";
            } finally {
                exited.countDown();
            }
        });
        RecordingSink sink = new RecordingSink();
        sink.failWrites = true;
        stream(dispatcher.start(request(TOKEN), true)).start(sink);

        assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertAllPermitsFree(dispatcher, 2);
        assertThat(dispatcher.runtimeStats().snapshot().cancellations()).isEqualTo(1);
    }

    @Test
    void aWriterBlockedOnAStalledClientKeepsThePermitUntilItReturns() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(args -> "done");
        RecordingSink stalled = new RecordingSink() {
            @Override
            public void complete(McpDispatchOutcome outcome) throws IOException {
                writing.countDown();
                await(unblock);
                super.complete(outcome);
            }
        };
        stream(dispatcher.start(request(TOKEN), true)).start(stalled);

        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(availablePermits(dispatcher))
                .as("the tool returned, but its stream is still being written")
                .isEqualTo(1);
        unblock.countDown();
        assertThat(stalled.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertAllPermitsFree(dispatcher, 2);
        assertThat(dispatcher.runtimeStats().snapshot().callCount()).isEqualTo(1);
    }

    @Test
    void aClientThatStopsReadingNeverDelaysTheTimeoutOrTheToolsInterruption() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    try {
                        for (int i = 1; ; i++) {
                            // Each report is a handoff to the outbox, even while the writer is stuck on a socket.
                            OperationProgress.current().report(PHASE, i, 0);
                            OperationProgress.current().checkCancelled();
                            Thread.sleep(2);
                        }
                    } catch (InterruptedException interrupted) {
                        return "interrupted";
                    } finally {
                        exited.countDown();
                    }
                },
                2,
                300);
        RecordingSink stuck = new RecordingSink() {
            @Override
            public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
                blocked.countDown();
                await(unblock);
                super.progress(token, event);
            }
        };
        long started = System.nanoTime();
        stream(dispatcher.start(request(TOKEN), true)).start(stuck);

        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exited.await(5, TimeUnit.SECONDS))
                .as("the timeout still stops the tool")
                .isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3_000);
        assertThat(dispatcher.runtimeStats().snapshot().timeouts()).isEqualTo(1);
        assertThat(availablePermits(dispatcher))
                .as("the stuck writer still holds the permit")
                .isEqualTo(1);
        unblock.countDown();
        assertThat(stuck.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stuck.messages.get(stuck.messages.size() - 2))
                .isEqualTo("complete " + new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE));
        assertAllPermitsFree(dispatcher, 2);
    }

    @Test
    void permitsAndOutcomesAreAccountedExactlyOnceUnderRaces() throws Exception {
        int calls = 300;
        McpDispatcher dispatcher = dispatcher(
                args -> {
                    OperationProgress.current().report(PHASE, 1, 1);
                    return "done";
                },
                calls,
                1);
        List<RecordingSink> sinks = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            McpStreamingCall call = stream(dispatcher.start(request(McpProgressToken.of(i)), true));
            RecordingSink sink = new RecordingSink();
            sinks.add(sink);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread canceller = new Thread(() -> {
                await(barrier);
                call.cancel();
            });
            canceller.start();
            await(barrier);
            if (i % 3 != 0) {
                call.start(sink);
            } else {
                sink.close();
            }
            canceller.join();
        }
        for (RecordingSink sink : sinks) {
            assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertAllPermitsFree(dispatcher, calls);
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        long completed = sinks.stream()
                .filter(sink -> sink.messages.stream().anyMatch(message -> message.startsWith("complete")))
                .count();
        assertThat(stats.callCount()).as("each call frees its permit once").isEqualTo(calls);
        assertThat(completed + stats.cancellations() + stats.timeouts() - timedOutAndCompleted(sinks))
                .as("each call ends one way")
                .isEqualTo(calls);
        assertThat(sinks)
                .allSatisfy(sink -> assertThat(sink.messages.stream().filter(message -> message.startsWith("complete")))
                        .hasSizeLessThanOrEqualTo(1));
        assertThat(reported).hasValue(0);
    }

    /** A timed-out call writes its final timeout response, so it is counted both as a timeout and as completed. */
    private static long timedOutAndCompleted(List<RecordingSink> sinks) {
        String timeout = "complete " + new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE);
        return sinks.stream().filter(sink -> sink.messages.contains(timeout)).count();
    }

    private McpDispatcher dispatcher(Function<McpArguments, Object> handler) {
        return dispatcher(handler, 2, 30_000);
    }

    private McpDispatcher dispatcher(Function<McpArguments, Object> handler, int maxConcurrent, long timeoutMillis) {
        McpTool scan =
                new McpTool("architecture_scan", "Scan.", McpToolSchema.NONE, BootUiPanels.ARCHITECTURE, true, handler);
        return new McpDispatcher(
                List.of(scan),
                List.of(),
                policy,
                "1.0",
                "x",
                50,
                maxConcurrent,
                timeoutMillis,
                (operation, failure) -> reported.incrementAndGet());
    }

    private static McpRequest request(McpProgressToken token) {
        return new McpRequest(
                "2.0",
                "tools/call",
                false,
                null,
                "architecture_scan",
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.MODERN,
                token);
    }

    private static McpStreamingCall stream(McpCallStart start) {
        assertThat(start).isInstanceOf(McpCallStart.Stream.class);
        return ((McpCallStart.Stream) start).call();
    }

    private static int availablePermits(McpDispatcher dispatcher) {
        return dispatcher.availableCallPermits();
    }

    private static void assertAllPermitsFree(McpDispatcher dispatcher, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (availablePermits(dispatcher) != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(availablePermits(dispatcher)).isEqualTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static class RecordingSink implements McpStreamSink {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();
        private volatile boolean failWrites;

        @Override
        public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
            fail();
            messages.add("progress " + (token.isText() ? token.text() : token.number()) + " " + event.progress()
                    + (event.total() == null ? "" : "/" + event.total()) + " " + event.message());
        }

        @Override
        public void heartbeat() throws IOException {
            fail();
            messages.add("heartbeat");
        }

        @Override
        public void complete(McpDispatchOutcome outcome) throws IOException {
            fail();
            messages.add("complete " + outcome);
        }

        @Override
        public void close() {
            if (closes.incrementAndGet() == 1) {
                messages.add("close");
                closed.countDown();
            }
        }

        private void fail() throws IOException {
            if (failWrites) {
                throw new IOException("Broken pipe");
            }
        }
    }

    private static final class AllowAll implements McpPanelPolicy {
        private volatile boolean disabled;

        @Override
        public boolean isEnabled(String panelId) {
            return !disabled;
        }

        @Override
        public String disabledReason(String panelId) {
            return "disabled";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "read-only";
        }
    }
}
