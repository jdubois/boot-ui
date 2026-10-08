package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import io.github.jdubois.bootui.engine.progress.ProgressPhase;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** MCP 2025-06-18 progress on a POST event stream, and cancellation by {@code notifications/cancelled}. */
class McpLegacyProgressTests {

    private static final ProgressPhase PHASE = ProgressPhase.of("Working");
    private static final McpProgressToken TOKEN = McpProgressToken.of(9);

    private final AtomicInteger reported = new AtomicInteger();

    @Test
    void requestKeysCompareNumbersByValueAndStayShortWhateverTheExponent() {
        assertThat(McpRequestKey.number(new BigDecimal("7"))).isEqualTo(McpRequestKey.number(new BigDecimal("7.00")));
        assertThat(McpRequestKey.number(new BigDecimal("70"))).isEqualTo(McpRequestKey.number(new BigDecimal("7E+1")));
        assertThat(McpRequestKey.number(new BigDecimal("0.0"))).isEqualTo(McpRequestKey.number(BigDecimal.ZERO));
        assertThat(McpRequestKey.number(new BigDecimal("7"))).isNotEqualTo(McpRequestKey.text("7"));
        assertThat(McpRequestKey.number(new BigDecimal("1E+999999999"))).hasSizeLessThan(32);
    }

    @Test
    void aLegacyProgressCallStreamsProgressThenItsLegacyResponse() throws Exception {
        McpDispatcher dispatcher = dispatcher(args -> {
            OperationProgress.current().report(PHASE, 1, 2);
            return "done";
        });
        Sink sink = new Sink();
        McpStreamingCall call = stream(dispatcher.start(call(TOKEN, "7"), true));
        assertThat(call.era()).isEqualTo(McpEra.LEGACY);
        call.start(sink);

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages).containsExactly("progress 1.0", "complete " + new ToolCallResult("done"), "close");
        // The tool's part may still be finishing after the writer closed the stream; it unregisters before its permit.
        awaitPermits(dispatcher, 2);
        assertThat(dispatcher.inFlightCalls())
                .as("the registration ends with the call")
                .isZero();
    }

    @Test
    void withoutATokenOrAStreamingClientALegacyCallStaysOneResponse() {
        McpDispatcher dispatcher = dispatcher(args -> "done");
        assertThat(dispatcher.start(call(null, "7"), true))
                .isEqualTo(new McpCallStart.Immediate(new ToolCallResult("done")));
        assertThat(dispatcher.start(call(TOKEN, "7"), false))
                .isEqualTo(new McpCallStart.Immediate(new ToolCallResult("done")));
    }

    @Test
    void closingALegacyStreamStopsWritingButNeverCancelsTheCall() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> finished = new AtomicReference<>();
        McpDispatcher dispatcher = dispatcher(args -> {
            OperationProgress.current().report(PHASE, 1, 0);
            await(release);
            finished.set("ran to the end");
            return "done";
        });
        Sink sink = new Sink();
        McpStreamingCall call = stream(dispatcher.start(call(TOKEN, "7"), true));
        call.start(sink);
        call.clientClosed();

        assertThat(sink.closed.await(5, TimeUnit.SECONDS))
                .as("the writer stops")
                .isTrue();
        assertThat(dispatcher.availableCallPermits()).as("the tool still runs").isEqualTo(1);
        release.countDown();
        awaitPermits(dispatcher, 2);
        assertThat(finished.get()).isEqualTo("ran to the end");
        assertThat(sink.messages).noneMatch(message -> message.startsWith("complete"));
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.cancellations()).isZero();
        assertThat(stats.callCount()).isEqualTo(1);
    }

    @Test
    void aLegacyClientThatLeavesBeforeItsStreamStartsReleasesTheCallAtOnce() throws Exception {
        AtomicReference<String> ran = new AtomicReference<>();
        McpDispatcher dispatcher = dispatcher(args -> {
            ran.set("ran");
            return "done";
        });
        McpStreamingCall call = stream(dispatcher.start(call(TOKEN, "7"), true));
        assertThat(dispatcher.availableCallPermits()).isEqualTo(1);

        call.clientClosed();

        assertThat(dispatcher.availableCallPermits())
                .as("nothing began, and there is nowhere left to answer")
                .isEqualTo(2);
        Sink sink = new Sink();
        call.start(sink);
        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isNull();
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.timeouts()).isZero();
        assertThat(stats.cancellations()).isEqualTo(1);
    }

    @Test
    void notificationsCancelledStopsAStreamingLegacyCallByItsId() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(this::runUntilCancelled, running);
        Sink sink = new Sink();
        McpStreamingCall call = stream(dispatcher.start(call(TOKEN, "7"), true));
        call.start(sink);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.dispatch(cancelled(McpRequestKey.text("7"))))
                .isEqualTo(new McpDispatchOutcome.NoResponse());

        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.messages).noneMatch(message -> message.startsWith("complete"));
        awaitPermits(dispatcher, 2);
        assertThat(dispatcher.runtimeStats().snapshot().cancellations()).isEqualTo(1);
        assertThat(dispatcher.inFlightCalls()).isZero();
    }

    @Test
    void notificationsCancelledStopsABlockingLegacyCallAndNumericIdsMatchByValue() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        McpDispatcher dispatcher = dispatcher(this::runUntilCancelled, running);
        AtomicReference<McpDispatchOutcome> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> outcome.set(((McpCallStart.Immediate)
                        dispatcher.start(callWithKey(null, McpRequestKey.number(new BigDecimal("12"))), true))
                .outcome()));
        caller.start();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.inFlightCalls()).isEqualTo(1);

        dispatcher.dispatch(cancelled(McpRequestKey.text("12")));
        assertThat(dispatcher.inFlightCalls())
                .as("the string \"12\" is not the number 12")
                .isEqualTo(1);
        dispatcher.dispatch(cancelled(McpRequestKey.number(new BigDecimal("12.0"))));
        caller.join(5_000);

        assertThat(outcome.get()).isEqualTo(new McpDispatchOutcome.Cancelled());
        assertThat(dispatcher.inFlightCalls()).isZero();
        assertThat(dispatcher.runtimeStats().snapshot().cancellations()).isEqualTo(1);
        assertThat(reported).hasValue(0);
    }

    @Test
    void unknownFinishedAndSharedIdsAreIgnoredSilently() throws Exception {
        CountDownLatch running = new CountDownLatch(2);
        McpDispatcher dispatcher = dispatcher(this::runUntilCancelled, running);
        assertThat(dispatcher.dispatch(cancelled(McpRequestKey.text("nobody"))))
                .isEqualTo(new McpDispatchOutcome.NoResponse());
        assertThat(dispatcher.dispatch(cancelled(null))).isEqualTo(new McpDispatchOutcome.NoResponse());

        List<Sink> sinks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Sink sink = new Sink();
            sinks.add(sink);
            stream(dispatcher.start(call(McpProgressToken.of(i), "same"), true)).start(sink);
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(cancelled(McpRequestKey.text("same")));
        Thread.sleep(100);
        assertThat(dispatcher.runtimeStats().snapshot().cancellations())
                .as("two local callers used the same id: never guess which one to cancel")
                .isZero();
        assertThat(dispatcher.inFlightCalls()).isEqualTo(2);
    }

    @Test
    void aCancellationSentWithAnIdIsNotANotification() {
        McpDispatcher dispatcher = dispatcher(args -> "done");
        McpRequest withId = new McpRequest(
                "2.0",
                "notifications/cancelled",
                false,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.LEGACY,
                null,
                McpRequestKey.text("1"),
                McpRequestKey.text("7"));
        assertThat(dispatcher.dispatch(withId))
                .isEqualTo(new McpDispatchOutcome.ProtocolError(
                        McpProtocol.METHOD_NOT_FOUND, "Unknown method: notifications/cancelled"));
    }

    private Object runUntilCancelled(McpArguments arguments, CountDownLatch running) {
        OperationProgress progress = OperationProgress.current();
        running.countDown();
        try {
            while (true) {
                progress.checkCancelled();
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            return "interrupted";
        }
    }

    private McpDispatcher dispatcher(Function<McpArguments, Object> handler) {
        McpTool scan =
                new McpTool("architecture_scan", "Scan.", McpToolSchema.NONE, BootUiPanels.ARCHITECTURE, true, handler);
        return new McpDispatcher(
                List.of(scan),
                List.of(),
                new AllowAll(),
                "1.0",
                "x",
                50,
                2,
                30_000,
                (operation, failure) -> reported.incrementAndGet());
    }

    private McpDispatcher dispatcher(
            java.util.function.BiFunction<McpArguments, CountDownLatch, Object> handler, CountDownLatch running) {
        return dispatcher(arguments -> handler.apply(arguments, running));
    }

    private static McpRequest call(McpProgressToken token, String id) {
        return callWithKey(token, McpRequestKey.text(id));
    }

    private static McpRequest callWithKey(McpProgressToken token, String requestKey) {
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
                McpEra.LEGACY,
                token,
                requestKey,
                null);
    }

    private static McpRequest cancelled(String requestKey) {
        return new McpRequest(
                "2.0",
                "notifications/cancelled",
                true,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.LEGACY,
                null,
                null,
                requestKey);
    }

    private static McpStreamingCall stream(McpCallStart start) {
        assertThat(start).isInstanceOf(McpCallStart.Stream.class);
        return ((McpCallStart.Stream) start).call();
    }

    private static void awaitPermits(McpDispatcher dispatcher, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.availableCallPermits() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(dispatcher.availableCallPermits()).isEqualTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Sink implements McpStreamSink {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
            messages.add("progress " + event.progress());
        }

        @Override
        public void heartbeat() {}

        @Override
        public void complete(McpDispatchOutcome outcome) {
            messages.add("complete " + outcome);
        }

        @Override
        public void close() {
            if (closed.getCount() > 0) {
                messages.add("close");
                closed.countDown();
            }
        }
    }

    private static final class AllowAll implements McpPanelPolicy {
        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "";
        }
    }
}
