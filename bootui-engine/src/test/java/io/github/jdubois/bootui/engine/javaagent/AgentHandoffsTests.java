package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunningHandoffs;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentHandoffsTests {

    private static final CorrelationContext REQUEST = new CorrelationContext(
            "r1",
            "e0",
            "trace-1",
            "span-1",
            "/orders/{id}",
            "OrderController#get",
            "tx-1",
            "orders-db",
            "linked-1",
            false);

    private final Recording sink = new Recording();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong allocated = new AtomicLong(10_000);
    private final RunningHandoffs running = new RunningHandoffs(4);
    private Instant now = Instant.parse("2026-10-03T08:00:00Z");
    private volatile boolean failReadingAllocations;

    @BeforeEach
    void resetBridge() {
        Bridges.reset();
    }

    @AfterEach
    void cleanUp() {
        Bridges.reset();
        assertThat(BootUiCorrelation.current()).isEqualTo(CorrelationContext.NONE);
    }

    private AgentHandoffs handoffs(RequestPhases phases, Duration maxHandoff) {
        AgentHandoffs handoffs = new AgentHandoffs(
                BootUiCorrelation::current,
                phases,
                maxHandoff,
                new Clock() {
                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now;
                    }
                },
                nanos::get,
                () -> {
                    if (failReadingAllocations) {
                        throw new IllegalStateException("allocations unavailable");
                    }
                    return allocated.get();
                },
                running);
        handoffs.setRuntimeEventSink(sink);
        return handoffs;
    }

    @Test
    void captureTakesTheOwnersIdsAndNeverTheTransactionOrDataSource() {
        AgentHandoffs handoffs = handoffs(null, null);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            Object[] snapshot = (Object[]) handoffs.capture();

            assertThat(snapshot)
                    .containsExactly(
                            "r1",
                            "e0",
                            "trace-1",
                            "span-1",
                            "/orders/{id}",
                            "OrderController#get",
                            "linked-1",
                            now.toEpochMilli(),
                            1_000_000_000L);
            assertThat(snapshot).doesNotContain("tx-1", "orders-db");
            for (Object value : snapshot) {
                assertThat(value == null || value instanceof String || value instanceof Long)
                        .as("only values the bridge's payload whitelist accepts: %s", value)
                        .isTrue();
            }
        }
    }

    @Test
    void captureLeavesBootUisOwnWorkAndUnownedWorkUnpropagated() throws Exception {
        AgentHandoffs handoffs = handoffs(null, null);

        assertThat(handoffs.capture()).as("no owner").isNull();
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            assertThat(handoffs.capture()).as("BootUI's own work").isNull();
        }
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(
                new CorrelationContext(null, null, "trace-only", "span", null, null, null, null, null, false))) {
            assertThat(handoffs.capture()).as("a trace id alone owns nothing").isNull();
        }
        AtomicReference<Object> onBootUiThread = new AtomicReference<>("unset");
        Thread thread = new Thread(
                () -> {
                    try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
                        onBootUiThread.set(handoffs.capture());
                    }
                },
                "bootui-journal");
        thread.start();
        thread.join();
        assertThat(onBootUiThread.get()).as("a bootui- thread").isNull();
    }

    @Test
    void reopenOpensAChildExecutionOfTheRequestAndPublishesItOnceAfterRestoringTheThread() {
        AgentHandoffs handoffs = handoffs(null, null);
        Object[] snapshot = snapshot(handoffs);
        CorrelationContext previous = CorrelationContext.forExecution("worker-job");
        sink.onOffer = event -> sink.contextAtOffer.add(BootUiCorrelation.current());

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(previous)) {
            nanos.addAndGet(3_000_000);
            AutoCloseable handle =
                    handoffs.reopen(new Object[] {snapshot, "com.acme.Job", "ThreadPoolExecutor.runWorker"});
            CorrelationContext inside = BootUiCorrelation.current();
            assertThat(inside.requestId()).isEqualTo("r1");
            assertThat(inside.executionId()).startsWith(ExecutionIds.ASYNC_PREFIX);
            assertThat(inside.traceId()).isEqualTo("trace-1");
            assertThat(inside.transactionId()).isNull();
            assertThat(running.snapshot()).singleElement().satisfies(r -> {
                assertThat(r.requestId()).isEqualTo("r1");
                assertThat(r.executionId()).isEqualTo(inside.executionId());
            });

            nanos.addAndGet(7_000_000);
            allocated.addAndGet(4_096);
            close(handle);
            close(handle);

            assertThat(BootUiCorrelation.current()).isEqualTo(previous);
            assertThat(sink.contextAtOffer)
                    .as("the previous context is back before publishing")
                    .containsExactly(previous);
            assertThat(running.size()).isZero();
            RuntimeEvent event = sink.single();
            assertThat(event.source()).isEqualTo(JournalSource.AGENT_EXECUTORS);
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.executionId()).isEqualTo(inside.executionId());
            assertThat(event.traceId()).isEqualTo("trace-1");
            assertThat(event.durationNanos()).isEqualTo(7_000_000);
            assertThat(event.epochMillis()).isEqualTo(now.toEpochMilli());
            assertThat(event.failedOrSlow()).isFalse();
            AsyncHandoffPayload payload = (AsyncHandoffPayload) event.payload();
            assertThat(payload.executionId()).isEqualTo(inside.executionId());
            assertThat(payload.parentExecutionId()).isEqualTo("e0");
            assertThat(payload.taskClass()).isEqualTo("com.acme.Job");
            assertThat(payload.hook()).isEqualTo("ThreadPoolExecutor.runWorker");
            assertThat(payload.queuedNanos()).isEqualTo(3_000_000);
            assertThat(payload.allocatedBytes()).isEqualTo(4_096L);
            assertThat(payload.afterResponse()).as("no phases: unknown").isNull();
            assertThat(payload.capped()).isFalse();
        }
    }

    @Test
    void aFailureIsRecordedByItsClassOnly() {
        AgentHandoffs handoffs = handoffs(null, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});

        @SuppressWarnings("unchecked")
        Consumer<Throwable> outcome = (Consumer<Throwable>) handle;
        outcome.accept(new IllegalStateException("card 4111-1111-1111-1111 declined"));
        close(handle);

        RuntimeEvent event = sink.single();
        AsyncHandoffPayload payload = (AsyncHandoffPayload) event.payload();
        assertThat(event.failedOrSlow()).isTrue();
        assertThat(payload.failed()).isTrue();
        assertThat(payload.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(payload.toString()).doesNotContain("4111");
    }

    @Test
    void aHandoffRunningPastItsDeadlineIsPublishedCapped() {
        AgentHandoffs handoffs = handoffs(null, Duration.ofSeconds(2));
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});
        nanos.addAndGet(Duration.ofSeconds(3).toNanos());
        close(handle);

        assertThat(((AsyncHandoffPayload) sink.single().payload()).capped()).isTrue();
    }

    @Test
    void afterResponseIsReadFromTheRequestsResponseMarker() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.RESPONSE);
        long responseAt = phases.markers("r1").responseAt();
        now = Instant.ofEpochSecond(responseAt / 1_000_000L, (responseAt % 1_000_000L) * 1_000L)
                .minusMillis(1);
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});
        nanos.addAndGet(Duration.ofMillis(5).toNanos());
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.afterResponse()).isTrue();
        assertThat(payload.afterResponseMicros()).isEqualTo(4_000L);
    }

    @Test
    void aHandoffEndingBeforeTheResponseIsNotAfterIt() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.RESPONSE);
        now = Instant.parse("2020-01-01T00:00:00Z");
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.afterResponse()).isFalse();
        assertThat(payload.afterResponseMicros()).isZero();
    }

    @Test
    void aHandoffEndingWhileItsRequestsHandlerStillRunsIsNotAfterTheResponse() {
        // The handler waits for the task, as a counterexample of work-after-response does: no response marker yet.
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.HANDLER);
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});
        nanos.addAndGet(Duration.ofMillis(5).toNanos());
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.afterResponse()).isFalse();
        assertThat(payload.afterResponseMicros()).isZero();
    }

    @Test
    void bodyCompletionBeforeResultPublicationWinsOverDelayedClose() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.HANDLER);
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "FutureTask", "hook"});
        nanos.addAndGet(5_000_000);
        ((Runnable) handle).run();
        phases.mark("r1", RequestPhase.RESPONSE);
        nanos.addAndGet(500_000_000);
        ((Runnable) handle).run();
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.bodyAfterResponse()).isFalse();
        assertThat(payload.bodyAfterResponseMicros()).isZero();
        assertThat(payload.responseAtMicros()).isEqualTo(phases.markers("r1").responseAt());
        assertThat(sink.single().durationNanos()).isEqualTo(505_000_000);
    }

    @Test
    void aBodyEndingAfterTheResponseIncludesALateTaskAndNotItsClosureDelay() {
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.RESPONSE);
        long responseAt = phases.markers("r1").responseAt();
        now = Instant.ofEpochSecond(responseAt / 1_000_000L, responseAt % 1_000_000L * 1_000L)
                .plusMillis(170);
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "FutureTask", "hook"});
        nanos.addAndGet(1_500_000);
        ((Runnable) handle).run();
        nanos.addAndGet(500_000_000);
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.bodyAfterResponse()).isTrue();
        assertThat(payload.bodyAfterResponseMicros()).isEqualTo(1_500);
        assertThat(payload.afterResponseMicros()).isEqualTo(501_500);
    }

    @Test
    void bodyCompletionUsesTheUnphasedRequestsEnd() {
        RequestPhases phases = new RequestPhases();
        phases.beginUnphased("r1");
        phases.end("r1");
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "FutureTask", "hook"});
        ((Runnable) handle).run();
        close(handle);

        assertThat(((AsyncHandoffPayload) sink.single().payload()).bodyAfterResponse())
                .isTrue();
    }

    @Test
    void aHandoffEndingAfterARequestThatMarkedNoResponseIsComparedWithItsEnd() {
        // A failed handler marks no response phase, but its request ended before this task did.
        RequestPhases phases = new RequestPhases();
        phases.begin("r1");
        phases.mark("r1", RequestPhase.HANDLER);
        phases.end("r1");
        long endedAt = phases.markers("r1").endedAt();
        now = Instant.ofEpochSecond(endedAt / 1_000_000L, (endedAt % 1_000_000L) * 1_000L)
                .minusMillis(1);
        AgentHandoffs handoffs = handoffs(phases, null);
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"});
        nanos.addAndGet(Duration.ofMillis(5).toNanos());
        close(handle);

        AsyncHandoffPayload payload = (AsyncHandoffPayload) sink.single().payload();
        assertThat(payload.afterResponse()).isTrue();
        assertThat(payload.afterResponseMicros()).isEqualTo(4_000L);
    }

    @Test
    void reopenOnAThreadAlreadyWorkingForTheRequestOpensNothing() {
        AgentHandoffs handoffs = handoffs(null, null);
        Object[] snapshot = snapshot(handoffs);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            assertThat(handoffs.reopen(new Object[] {snapshot, "Task", "hook"})).isNull();
        }
        assertThat(handoffs.reopen(new Object[] {"not a snapshot", "Task", "hook"}))
                .isNull();
        assertThat(handoffs.reopen(null)).isNull();
        assertThat(sink.events).isEmpty();
    }

    @Test
    void aScheduledRunsSnapshotKeepsItsExecution() {
        AgentHandoffs handoffs = handoffs(null, null);
        Object[] snapshot;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forExecution("job-1"))) {
            snapshot = (Object[]) handoffs.capture();
        }
        AutoCloseable handle = handoffs.reopen(new Object[] {snapshot, "Task", "hook"});
        assertThat(BootUiCorrelation.current().executionId()).isEqualTo("job-1");
        close(handle);
        assertThat(sink.single().executionId()).isEqualTo("job-1");
    }

    @Test
    void aReopenThatFailsLeavesThePooledWorkersContextAndTheRegistryAsTheyWere() {
        AgentHandoffs handoffs = handoffs(null, null);
        Object[] snapshot = snapshot(handoffs);
        CorrelationContext previous = CorrelationContext.forExecution("worker-job");
        failReadingAllocations = true;

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(previous)) {
            assertThat(handoffs.reopen(new Object[] {snapshot, "Task", "hook"})).isNull();

            assertThat(BootUiCorrelation.current()).isEqualTo(previous);
        }
        assertThat(running.size()).isZero();
        assertThat(sink.events).isEmpty();
    }

    @Test
    void concurrentHandoffsOfOneScheduledRunAreEachRunningUntilTheirOwnClose() throws Exception {
        AgentHandoffs handoffs = handoffs(null, null);
        Object[] snapshot;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forExecution("job-1"))) {
            snapshot = (Object[]) handoffs.capture();
        }
        AutoCloseable first = handoffs.reopen(new Object[] {snapshot, "First", "hook"});
        java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(() -> {
            AutoCloseable second = handoffs.reopen(new Object[] {snapshot, "Second", "hook"});
            opened.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            close(second);
        });
        worker.start();
        assertThat(opened.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(running.snapshot())
                .extracting(RunningHandoffs.Running::executionId, RunningHandoffs.Running::taskClass)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("job-1", "First"),
                        org.assertj.core.groups.Tuple.tuple("job-1", "Second"));
        close(first);
        assertThat(running.snapshot())
                .extracting(RunningHandoffs.Running::taskClass)
                .containsExactly("Second");
        release.countDown();
        worker.join();
        assertThat(running.size()).isZero();
        assertThat(sink.events).hasSize(2);
    }

    @Test
    void aSinkThatDoesNotRecordTheSourceGetsNothing() {
        AgentHandoffs handoffs = handoffs(null, null);
        sink.recording = false;
        close(handoffs.reopen(new Object[] {snapshot(handoffs), "Task", "hook"}));
        assertThat(sink.events).isEmpty();
    }

    @Test
    void throughTheRealBridgeATaskRunsAsAChildExecutionAndClosingSubmitsNothing() throws Exception {
        Bridges.StubAgent.install();
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "app", "app@1", "dev", List.of("com.example"));
        AgentHandoffs handoffs = handoffs(null, null);
        claim.attach(handoffs);
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> seen.set(BootUiCorrelation.current()), null);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                    .isTrue();
        }
        Map<String, Object> keyedBefore = executors();
        Thread worker = new Thread(() -> TaskPropagation.runTask(task), "pool-1-thread-1");
        worker.start();
        worker.join();

        assertThat(seen.get().requestId()).isEqualTo("r1");
        assertThat(ExecutionIds.isAsync(seen.get().executionId())).isTrue();
        Map<String, Object> after = executors();
        assertThat(after.get("refused"))
                .as("every captured value passes the whitelist")
                .isEqualTo(0L);
        assertThat(after.get("keyed"))
                .as("publishing from close keyed no new task")
                .isEqualTo(keyedBefore.get("keyed"));
        assertThat(sink.single().requestId()).isEqualTo("r1");
        assertThat(sink.single().thread()).isEqualTo("pool-1-thread-1");

        claim.disarm();
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            assertThat(TaskPropagation.submitted(new FutureTask<>(() -> null), TaskPropagation.KEY_THREAD_POOL))
                    .as("a disarmed claim keys nothing")
                    .isFalse();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> executors() {
        return (Map<String, Object>) AgentBridge.status().get("executors");
    }

    private static Object[] snapshot(AgentHandoffs handoffs) {
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(REQUEST)) {
            return (Object[]) handoffs.capture();
        }
    }

    private static void close(AutoCloseable handle) {
        try {
            handle.close();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class Recording implements RuntimeEventSink {

        final List<RuntimeEvent> events = new ArrayList<>();
        final List<CorrelationContext> contextAtOffer = new ArrayList<>();
        Consumer<RuntimeEvent> onOffer = event -> {};
        boolean recording = true;

        @Override
        public synchronized boolean offer(RuntimeEvent event) {
            onOffer.accept(event);
            events.add(event);
            return true;
        }

        @Override
        public boolean records(JournalSource source) {
            return recording;
        }

        synchronized RuntimeEvent single() {
            assertThat(events).hasSize(1);
            return events.get(0);
        }
    }
}
