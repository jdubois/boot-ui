package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The executor sensor's bridge semantics (PLAN-v2 D32, M5-2), without the agent's advice: tasks keyed and applied. */
class TaskPropagationTests {

    private final ThreadLocal<Object> owner = new ThreadLocal<>();
    private final List<String> reopened = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final List<Boolean> bodyFailures = new ArrayList<>();
    private Supplier<Object> capture;
    private Function<Object, AutoCloseable> reopen;

    @BeforeEach
    void claim() {
        AgentBridge.reset();
        AgentBridge.install(request -> Map.of("status", "ok"));
        claimWith(List.of());
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void anOwnedTaskIsAppliedOnceWithItsOwnersSnapshot() {
        Runnable task = () -> {};
        owner.set(snapshot("r1"));

        assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                .isTrue();
        owner.remove();
        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        TaskPropagation.exit(handle, null);

        assertThat(reopened).containsExactly("r1 " + task.getClass().getName() + " ThreadPoolExecutor.runWorker");
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
    }

    @Test
    void anOwnedTaskPastTheCapIsCountedAsOverflowAndRunsUnowned() {
        long generation = AgentBridge.current().generation;
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Object delayed = new Object();
            queued.add(delayed);
            TaskSnapshots.TASKS.put(delayed, generation, snapshot("delayed"), 0L);
        }
        Object keyedBefore = TaskPropagation.status().get("keyed");
        Runnable task = () -> {};
        owner.set(snapshot("r1"));

        assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                .isFalse();
        owner.remove();

        assertThat(counter("overflow")).isEqualTo(1L);
        assertThat(counter("ambiguous")).isZero();
        assertThat(counter("pending")).isEqualTo(TaskSnapshots.MAX_PENDING);
        assertThat(TaskPropagation.status().get("keyed")).isEqualTo(keyedBefore);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(reopened).isEmpty();
        // A queued task that runs frees its slot for the next submission.
        TaskPropagation.exit(TaskPropagation.enter(queued.get(0), TaskPropagation.APPLY_RUN_WORKER), null);
        owner.set(snapshot("r2"));
        assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                .isTrue();
        owner.remove();
        TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), null);
        assertThat(reopened)
                .containsExactly(
                        "delayed java.lang.Object ThreadPoolExecutor.runWorker",
                        "r2 " + task.getClass().getName() + " ThreadPoolExecutor.runWorker");
        assertThat(counter("overflow")).isEqualTo(1L);
    }

    @Test
    void aTaskPastTheCapIsCountedOnceAndOnlyWhenTheExecutorAcceptedIt() {
        long generation = AgentBridge.current().generation;
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Object delayed = new Object();
            queued.add(delayed);
            TaskSnapshots.TASKS.put(delayed, generation, snapshot("delayed"), 0L);
        }
        Runnable task = () -> {};
        owner.set(snapshot("r1"));

        // ThreadPoolExecutor.execute with a full queue: the queue refuses it, then a new worker takes it.
        assertThat(TaskPropagation.offer(new java.util.concurrent.SynchronousQueue<>(), task))
                .isFalse();
        int outcome = TaskPropagation.workerOffered(task);
        TaskPropagation.workerAdded(outcome, task, true);
        // A task the executor rejects never runs, and is not counted.
        Runnable rejected = () -> {};
        TaskPropagation.workerAdded(TaskPropagation.workerOffered(rejected), rejected, false);
        owner.remove();

        assertThat(outcome).isEqualTo(TaskPropagation.OVERFLOWED);
        assertThat(counter("overflow")).isEqualTo(1L);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(queued).hasSize(TaskSnapshots.MAX_PENDING);
    }

    @Test
    void aRefusedWorkerStartCannotReleaseTheSameTaskAdmittedAfterRoomWasFreed() {
        long generation = AgentBridge.current().generation;
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Object delayed = new Object();
            queued.add(delayed);
            TaskSnapshots.TASKS.put(delayed, generation, snapshot("delayed"), 0L);
        }
        Runnable shared = () -> {};
        owner.set(snapshot("a"));
        int refused = TaskPropagation.workerOffered(shared);
        assertThat(refused).isEqualTo(TaskPropagation.OVERFLOWED);

        TaskPropagation.exit(TaskPropagation.enter(queued.get(0), TaskPropagation.APPLY_RUN_WORKER), null);
        owner.set(snapshot("b"));
        assertThat(TaskPropagation.submitted(shared, TaskPropagation.KEY_THREAD_POOL))
                .isTrue();

        TaskPropagation.workerAdded(refused, shared, false);
        TaskPropagation.exit(TaskPropagation.enter(shared, TaskPropagation.APPLY_RUN_WORKER), null);
        owner.remove();

        assertThat(reopened).last().asString().startsWith("b ");
    }

    @Test
    void aRefusedQueueOfferCannotReleaseTheSameTaskAdmittedWhileOfferWasInFlight() {
        long generation = AgentBridge.current().generation;
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Object delayed = new Object();
            queued.add(delayed);
            TaskSnapshots.TASKS.put(delayed, generation, snapshot("delayed"), 0L);
        }
        Runnable shared = () -> {};
        java.util.concurrent.BlockingQueue<Object> refusing = new java.util.concurrent.ArrayBlockingQueue<Object>(1) {
            @Override
            public boolean offer(Object task) {
                TaskPropagation.exit(TaskPropagation.enter(queued.get(0), TaskPropagation.APPLY_RUN_WORKER), null);
                Object previousOwner = owner.get();
                owner.set(snapshot("b"));
                assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                        .isTrue();
                owner.set(previousOwner);
                return false;
            }
        };
        owner.set(snapshot("a"));

        assertThat(TaskPropagation.offer(refusing, shared)).isFalse();
        TaskPropagation.exit(TaskPropagation.enter(shared, TaskPropagation.APPLY_RUN_WORKER), null);
        owner.remove();

        assertThat(reopened).last().asString().startsWith("b ");
    }

    @Test
    void executorSelfTestMarkersAreAppliedWhenOldLiveTasksFillTheOrdinaryLimit() {
        long generation = AgentBridge.current().generation;
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Object delayed = new Object();
            queued.add(delayed);
            TaskSnapshots.TASKS.put(delayed, generation, snapshot("old"), 0L);
        }
        claimWith(List.of());

        Runnable marker = () -> {};
        TaskPropagation.beginSelfTest();
        assertThat(TaskPropagation.submitted(marker, TaskPropagation.KEY_THREAD_POOL))
                .isTrue();
        assertThat(TaskPropagation.enter(marker, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        Map<String, Object> selfTest = TaskPropagation.endSelfTest();

        assertThat(selfTest.get("keyed").toString()).contains("ThreadPoolExecutor.addWorker=1");
        assertThat(selfTest.get("applied").toString()).contains("ThreadPoolExecutor.runWorker=1");
        assertThat(queued).hasSize(TaskSnapshots.MAX_PENDING);
        assertThat(TaskSnapshots.TASKS.retainedEntries.get())
                .isLessThanOrEqualTo(TaskSnapshots.MAX_PENDING + TaskSnapshots.SELF_TEST_RESERVE);
    }

    @Test
    void threadSelfTestMarkersAreAppliedWhenOldLiveTasksFillTheOrdinaryLimit() {
        long generation = AgentBridge.current().generation;
        List<Thread> retained = new ArrayList<>();
        for (int i = 0; i < TaskSnapshots.MAX_PENDING; i++) {
            Thread thread = new Thread();
            retained.add(thread);
            TaskSnapshots.THREADS.put(thread, generation, snapshot("old"), 0L);
        }
        claimWith(List.of());

        Thread marker = new Thread();
        ThreadPropagation.beginSelfTest();
        assertThat(ThreadPropagation.starting(marker, ThreadPropagation.KEY_THREAD_START))
                .isTrue();
        assertThat(ThreadPropagation.enter(marker, null, ThreadPropagation.APPLY_THREAD_RUN))
                .isNull();
        Map<String, Object> selfTest = ThreadPropagation.endSelfTest();

        assertThat(selfTest.get("keyed").toString()).contains("Thread.start=1");
        assertThat(selfTest.get("applied").toString()).contains("Thread.run=1");
        assertThat(retained).hasSize(TaskSnapshots.MAX_PENDING);
        assertThat(TaskSnapshots.THREADS.retainedEntries.get())
                .isLessThanOrEqualTo(TaskSnapshots.MAX_PENDING + TaskSnapshots.SELF_TEST_RESERVE);
    }

    @Test
    void anOwnedTaskCarriesTheSubmittingNodesStampToItsFragment() {
        AgentBridge.reset();
        AgentBridge.install(request -> Map.of("status", "ok"));
        Object[] execution = new Object[] {"00000000000000ab", "async-00000000000000cd", null, null};
        capture = owner::get;
        reopen = argument -> {
            owner.set(execution);
            return (AutoCloseable) owner::remove;
        };
        long token = (Long) AgentBridge.claim(
                        request(List.of(), List.of(TaskPropagation.SENSOR, CodePaths.SENSOR)), capture, reopen)
                .get("token");
        int service = CodeInventory.methodId("com.example.Shop#order()V");
        int worker = CodeInventory.methodId("com.example.Mailer#send()V");
        Runnable task = () -> {};
        Runnable other = () -> {};
        long[] submitter = new long[1];

        owner.set(new Object[] {"00000000000000ab", null, null, null});
        int open = CodePaths.enter(service);
        submitter[0] = CodePaths.stamp();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        CodePaths.exit(open);
        // The same task submitted by the same owner from two nodes keeps no stamp.
        open = CodePaths.enter(service);
        TaskPropagation.submitted(other, TaskPropagation.KEY_THREAD_POOL);
        int nested = CodePaths.enter(worker);
        TaskPropagation.submitted(other, TaskPropagation.KEY_THREAD_POOL);
        CodePaths.exit(nested);
        CodePaths.exit(open);
        owner.remove();

        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        CodePaths.exit(CodePaths.enter(worker));
        TaskPropagation.exit(handle, null);
        handle = TaskPropagation.enter(other, TaskPropagation.APPLY_RUN_WORKER);
        CodePaths.exit(CodePaths.enter(worker));
        TaskPropagation.exit(handle, null);
        TaskPropagation.exit(TaskPropagation.enter(other, TaskPropagation.APPLY_RUN_WORKER), null);

        List<long[]> blobs = new ArrayList<>();
        CodePaths.drain(token, blobs::add);
        assertThat(blobs).hasSize(4);
        long[] request = blobs.get(0);
        long[] handoff = blobs.get(2);
        assertThat(handoff[CodePaths.H_EXECUTION]).isEqualTo(0xcdL);
        assertThat(handoff[CodePaths.H_SUBMITTER]).isEqualTo(submitter[0]).isNotZero();
        assertThat(CodePaths.stampSequence(submitter[0])).isEqualTo(request[CodePaths.H_SEQUENCE]);
        assertThat(CodePaths.stampMethod(submitter[0])).isEqualTo(service);
        assertThat(blobs.get(3)[CodePaths.H_SUBMITTER]).isZero();
        // Once the work is over, the thread's next fragment has no submitter.
        assertThat(CodePaths.stamp()).isZero();
    }

    @Test
    void workRunOnTheSubmittingThreadInsideOtherWorkRestoresItsSubmitter() {
        AgentBridge.reset();
        AgentBridge.install(request -> Map.of("status", "ok"));
        Object[] execution = new Object[] {"00000000000000ab", "async-00000000000000cd", null, null};
        capture = owner::get;
        reopen = argument -> {
            Object previous = owner.get();
            owner.set(execution);
            return (AutoCloseable) () -> owner.set(previous);
        };
        long token = (Long) AgentBridge.claim(
                        request(List.of(), List.of(TaskPropagation.SENSOR, CodePaths.SENSOR)), capture, reopen)
                .get("token");
        int service = CodeInventory.methodId("com.example.Shop#order()V");
        int worker = CodeInventory.methodId("com.example.Mailer#send()V");
        Runnable outer = () -> {};
        Runnable inner = () -> {};
        long[] stamps = new long[2];

        owner.set(new Object[] {"00000000000000ab", null, null, null});
        int open = CodePaths.enter(service);
        stamps[0] = CodePaths.stamp();
        TaskPropagation.submitted(outer, TaskPropagation.KEY_THREAD_POOL);
        CodePaths.exit(open);
        owner.remove();

        Object outerHandle = TaskPropagation.enter(outer, TaskPropagation.APPLY_RUN_WORKER);
        // The outer work submits more work, which a caller-runs executor runs right here.
        open = CodePaths.enter(worker);
        stamps[1] = CodePaths.stamp();
        TaskPropagation.submitted(inner, TaskPropagation.KEY_THREAD_POOL);
        CodePaths.exit(open);
        Object innerHandle = TaskPropagation.enter(inner, TaskPropagation.APPLY_RUN_WORKER);
        CodePaths.exit(CodePaths.enter(service));
        TaskPropagation.exit(innerHandle, null);
        // Back in the outer work: its next fragment still names the outer submitter.
        CodePaths.exit(CodePaths.enter(worker));
        TaskPropagation.exit(outerHandle, null);

        List<long[]> blobs = new ArrayList<>();
        CodePaths.drain(token, blobs::add);
        assertThat(stamps[0]).isPositive();
        assertThat(stamps[1]).isPositive();
        assertThat(blobs.stream().map(blob -> blob[CodePaths.H_SUBMITTER]).toList())
                .containsExactly(0L, stamps[0], stamps[1], stamps[0]);
    }

    @Test
    void theSameOwnerSubmittingTwiceAtDifferentTimesIsNotAmbiguous() {
        Runnable task = () -> {};
        owner.set(new Object[] {"r1", null, null, null, null, null, null, 1L, 10L});
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.set(new Object[] {"r1", null, null, null, null, null, null, 2L, 20L});
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();

        TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), null);
        TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), null);

        assertThat(reopened).hasSize(2).allMatch(line -> line.startsWith("r1 "));
    }

    @Test
    void twoOwnersBeforeARunNeverCrossAndStayUnownedUntilEveryPendingRunIsDone() {
        Runnable task = () -> {};
        owner.set(snapshot("A"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.set(snapshot("B"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        owner.set(snapshot("C"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();

        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(reopened).isEmpty();
        assertThat(counter("ambiguous")).isPositive();
    }

    @Test
    void anUnownedSubmissionOfAPendingTaskMakesItUnowned() {
        Runnable task = () -> {};
        owner.set(snapshot("A"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);

        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(reopened).isEmpty();
    }

    @Test
    void payloadsThatCouldPinAClassLoaderAreRefused() {
        for (Object payload : List.of(
                new Object[] {"r1", String.class},
                new Object[] {"r1", List.of("x")},
                new Object[] {"r1", new Object[] {"nested"}},
                new String[] {"r1"},
                "r1",
                new Object[] {"r1", new Object()})) {
            Runnable task = () -> {};
            owner.set(payload);

            assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                    .isFalse();
            assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                    .isNull();
        }
        assertThat(counter("refused")).isEqualTo(6L);
        assertThat(reopened).isEmpty();
    }

    @Test
    void aSnapshotFromAnEarlierClaimIsNeverReopened() {
        Runnable task = () -> {};
        owner.set(snapshot("r1"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        claimWith(List.of());

        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        assertThat(counter("stale")).isEqualTo(1L);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void overlappingGenerationsStayUnownedInEitherExecutionOrder(boolean oldFirst, boolean newOwned) throws Exception {
        Runnable task = () -> {};
        owner.set(snapshot("same-owner"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        CountDownLatch oldGate = new CountDownLatch(1);
        CountDownLatch newGate = new CountDownLatch(1);
        FutureTask<Object> oldRun = blockedRun(task, oldGate);
        FutureTask<Object> newRun = blockedRun(task, newGate);
        Thread oldWorker = new Thread(oldRun, "old-generation");
        Thread newWorker = new Thread(newRun, "new-generation");
        oldWorker.start();
        newWorker.start();
        try {
            claimWith(List.of());
            if (!newOwned) {
                owner.remove();
            }
            assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                    .isTrue();
            assertThat(counter("pending")).isEqualTo(1L);

            CountDownLatch firstGate = oldFirst ? oldGate : newGate;
            FutureTask<Object> firstRun = oldFirst ? oldRun : newRun;
            CountDownLatch lastGate = oldFirst ? newGate : oldGate;
            FutureTask<Object> lastRun = oldFirst ? newRun : oldRun;
            firstGate.countDown();
            assertThat(firstRun.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(counter("pending")).isEqualTo(1L);
            lastGate.countDown();
            assertThat(lastRun.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(counter("pending")).isZero();
            assertThat(reopened).isEmpty();

            owner.set(snapshot("fresh"));
            TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
            TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), null);
            assertThat(reopened).hasSize(1).first().asString().startsWith("fresh ");
        } finally {
            oldGate.countDown();
            newGate.countDown();
            oldWorker.join(5000);
            newWorker.join(5000);
        }
    }

    private static FutureTask<Object> blockedRun(Object task, CountDownLatch gate) {
        return new FutureTask<>(() -> {
            assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
            return TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        });
    }

    @Test
    void skipCountersAndPendingApplicationStayQuietWhenTheSensorIsOff() {
        RunnableScheduledFuture<Void> periodic = new PeriodicTask();
        Object task = new Object();
        owner.set(snapshot("r1"));
        long token = claimWith(List.of());
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        AgentBridge.disarm(token);
        assertQuietSensor(periodic, task);

        claimWith(List.of());
        Object omittedTask = new Object();
        TaskPropagation.submitted(omittedTask, TaskPropagation.KEY_THREAD_POOL);
        Map<String, Object> request = request(List.of());
        request.put("sensors", List.of());
        AgentBridge.claim(request, capture, reopen);
        assertQuietSensor(periodic, omittedTask);

        claimWith(List.of());
        Object disabledTask = new Object();
        TaskPropagation.submitted(disabledTask, TaskPropagation.KEY_THREAD_POOL);
        TaskPropagation.disable(AgentBridge.current().generation, "test");
        assertQuietSensor(periodic, disabledTask);
    }

    private void assertQuietSensor(RunnableScheduledFuture<Void> periodic, Object task) {
        Map<String, Object> before = TaskPropagation.status();
        TaskPropagation.scheduled(periodic);
        TaskPropagation.delayedCreated(new Object(), 1);
        TaskPropagation.confirm(TaskPropagation.KEYED_OWNED, TaskPropagation.KEY_THREAD_POOL);
        TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        TaskPropagation.submitted(new Object(), TaskPropagation.KEY_THREAD_POOL);
        Map<String, Object> after = TaskPropagation.status();
        for (String name : List.of(
                "keyed",
                "applied",
                "stale",
                "ambiguous",
                "refused",
                "periodicSkipped",
                "virtualSkipped",
                "skippedTasks",
                "skippedThreads",
                "failures")) {
            assertThat(after.get(name)).as(name).isEqualTo(before.get(name));
        }
        assertThat(reopened).isEmpty();
        assertThat(counter("pending")).isZero();
    }

    @Test
    void virtualContinuationSkipCountersStopWithoutTheExecutorsSensor() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 21);
        Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
        Thread continuation = (Thread) Class.forName("java.lang.Thread$Builder")
                .getMethod("unstarted", Runnable.class)
                .invoke(builder, (Runnable) () -> {});
        TaskPropagation.adapterCreated(new Object(), continuation);
        assertThat(counter("virtualSkipped")).isEqualTo(1L);

        long token = claimWith(List.of());
        AgentBridge.disarm(token);
        TaskPropagation.adapterCreated(new Object(), continuation);
        Map<String, Object> request = request(List.of());
        request.put("sensors", List.of());
        AgentBridge.claim(request, capture, reopen);
        TaskPropagation.adapterCreated(new Object(), continuation);
        claimWith(List.of());
        TaskPropagation.disable(AgentBridge.current().generation, "test");
        TaskPropagation.adapterCreated(new Object(), continuation);
        assertThat(counter("virtualSkipped")).isEqualTo(1L);
    }

    static final class PeriodicTask extends FutureTask<Void> implements RunnableScheduledFuture<Void> {

        PeriodicTask() {
            super(() -> null);
        }

        @Override
        public boolean isPeriodic() {
            return true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }

    @Test
    void nothingIsKeyedWithoutTheSensorOrWhileDisarmed() {
        Runnable task = () -> {};
        owner.set(snapshot("r1"));
        AgentBridge.reset();
        AgentBridge.install(request -> Map.of("status", "ok"));
        Map<String, Object> request = request(List.of());
        request.put("sensors", List.of());
        AgentBridge.claim(request, capture, reopen);

        assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                .isFalse();

        long token = claimWith(List.of());
        AgentBridge.disarm(token);
        assertThat(TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL))
                .isFalse();
    }

    @Test
    void wrappersThatAlreadyPropagateAreNeverKeyedAndSkippedThreadsNeverApply() {
        claimWith(List.of(TaskPropagationTests.class.getName()));
        owner.set(snapshot("r1"));
        Runnable wrapper = () -> {};

        assertThat(TaskPropagation.submitted(wrapper, TaskPropagation.KEY_THREAD_POOL))
                .isFalse();
        assertThat(counter("skippedTasks")).isEqualTo(1L);

        claimWith(List.of());
        Runnable task = new Runnable() {
            @Override
            public void run() {}
        };
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        String name = Thread.currentThread().getName();
        Thread.currentThread().setName("vert.x-worker-thread-0");
        try {
            assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                    .isNull();
        } finally {
            Thread.currentThread().setName(name);
        }
        assertThat(counter("skippedThreads")).isEqualTo(1L);
    }

    @Test
    void theFailureIsToldBeforeClose() {
        Runnable task = () -> {};
        owner.set(snapshot("r1"));
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        IllegalStateException boom = new IllegalStateException("boom");

        TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), boom);

        assertThat(failures).containsExactly(boom);
        assertThat(reopened).hasSize(1);
    }

    @Test
    void aThrownFutureTailFailureIsDistinguishedFromBodyFailures() {
        FutureTask<Object> thrown = new FutureTask<>(() -> null) {
            @Override
            protected void done() {
                throw new IllegalStateException("tail");
            }
        };
        owner.set(snapshot("r1"));
        TaskPropagation.submitted(thrown, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        assertThatThrownBy(() -> TaskPropagation.runTask(thrown)).isInstanceOf(IllegalStateException.class);
        FutureTask<Object> deferred = new FutureTask<>(() -> {
            throw new IllegalArgumentException("body");
        });
        owner.set(snapshot("r2"));
        TaskPropagation.submitted(deferred, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        TaskPropagation.runTask(deferred);
        Runnable plain = () -> {
            throw new IllegalArgumentException("plain body");
        };
        owner.set(snapshot("r3"));
        TaskPropagation.submitted(plain, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();
        assertThatThrownBy(() -> TaskPropagation.runTask(plain)).isInstanceOf(IllegalArgumentException.class);

        assertThat(bodyFailures).containsExactly(false, true, true);
        assertThat(failures).hasSize(3);
    }

    @Test
    void aLegacyConsumerOnlyHandleStillReceivesFailures() {
        class LegacyHandle implements AutoCloseable, Consumer<Throwable> {
            boolean closed;

            @Override
            public void accept(Throwable failure) {
                failures.add(failure);
            }

            @Override
            public void close() {
                closed = true;
            }
        }
        LegacyHandle handle = new LegacyHandle();
        IllegalStateException failure = new IllegalStateException("body");
        TaskPropagation.exitHandle(handle, failure);

        assertThat(failures).containsExactly(failure);
        assertThat(handle.closed).isTrue();
    }

    @Test
    void aSelfTestTaskIsRecognizedByItsMarkerAndNeverReachesTheEngine() {
        Runnable task = () -> {};
        TaskPropagation.beginSelfTest();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        Map<String, Object> keyedOnly = TaskPropagation.endSelfTest();
        TaskPropagation.beginSelfTest();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();

        Map<String, Object> result = TaskPropagation.endSelfTest();

        assertThat(keyedOnly.get("keyed").toString()).contains("ThreadPoolExecutor.addWorker=1");
        assertThat(result.get("applied").toString()).contains("ThreadPoolExecutor.runWorker=1");
        assertThat(reopened).isEmpty();
    }

    @Test
    void entriesReclaimedWhilePendingAreCountedAsNeverApplied() throws InterruptedException {
        owner.set(snapshot("r1"));
        for (int i = 0; i < 100; i++) {
            TaskPropagation.submitted(new Object(), TaskPropagation.KEY_THREAD_POOL);
        }
        owner.remove();
        for (int i = 0; i < 50 && counter("neverApplied") == 0; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertThat(counter("neverApplied")).isPositive();
    }

    @Test
    void eachCompletableFutureTaskClassHasItsOwnApplyHook() {
        assertThat(TaskPropagation.asyncHook(new Object())).isEqualTo(-1);
        assertThat(TaskPropagation.asyncHook(asyncSupply())).isEqualTo(TaskPropagation.APPLY_ASYNC_SUPPLY);
        assertThat(TaskPropagation.asyncHook(asyncRun())).isEqualTo(TaskPropagation.APPLY_ASYNC_RUN);
    }

    @Test
    void aSelfTestAsyncTaskIsLeftByThePoolForItsOwnHookToCount() {
        Runnable task = asyncSupply();
        TaskPropagation.beginSelfTest();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);

        Object deferred = TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        Object own = TaskPropagation.enter(task, TaskPropagation.APPLY_ASYNC_SUPPLY);
        TaskPropagation.exit(own, null);
        TaskPropagation.exit(deferred, null);
        Map<String, Object> result = TaskPropagation.endSelfTest();

        assertThat(deferred).isNotNull();
        assertThat(result.get("applied").toString())
                .contains("ThreadPoolExecutor.runWorker=0")
                .contains("CompletableFuture.AsyncSupply=1")
                .contains("CompletableFuture.AsyncRun=0");
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
    }

    @Test
    void aSelfTestAsyncTaskWhoseOwnHookIsMissingIsCountedByNoHookAndReleased() {
        Runnable task = asyncRun();
        TaskPropagation.beginSelfTest();
        TaskPropagation.submitted(task, TaskPropagation.KEY_THREAD_POOL);

        TaskPropagation.exit(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER), null);
        Map<String, Object> result = TaskPropagation.endSelfTest();

        assertThat(result.get("applied").toString())
                .contains("ThreadPoolExecutor.runWorker=0")
                .contains("CompletableFuture.AsyncRun=0");
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_ASYNC_RUN)).isNull();
        assertThat(counter("pending")).isZero();
    }

    @Test
    void thePoolAppliesAnAsyncTaskUntilItsOwnClassHookIsVerified() {
        Runnable supply = asyncSupply();
        Runnable run = asyncRun();
        TaskPropagation.asyncApplies(TaskPropagation.APPLY_ASYNC_SUPPLY, true);
        owner.set(snapshot("r1"));
        TaskPropagation.submitted(supply, TaskPropagation.KEY_THREAD_POOL);
        TaskPropagation.submitted(run, TaskPropagation.KEY_THREAD_POOL);
        owner.remove();

        assertThat(TaskPropagation.enter(supply, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
        TaskPropagation.exit(TaskPropagation.enter(supply, TaskPropagation.APPLY_ASYNC_SUPPLY), null);
        TaskPropagation.exit(TaskPropagation.enter(run, TaskPropagation.APPLY_RUN_WORKER), null);
        assertThat(TaskPropagation.enter(run, TaskPropagation.APPLY_ASYNC_RUN)).isNull();

        assertThat(reopened)
                .containsExactly(
                        "r1 " + supply.getClass().getName() + " CompletableFuture.AsyncSupply",
                        "r1 " + run.getClass().getName() + " ThreadPoolExecutor.runWorker");
        Map<String, Object> executors = executors();
        assertThat(executors)
                .containsEntry("asyncSupplyApplies", true)
                .containsEntry("asyncRunApplies", false)
                .containsEntry("asyncApplies", false);
    }

    @Test
    void resettingTheBridgeForgetsWhichAsyncHooksWereVerified() {
        TaskPropagation.asyncApplies(TaskPropagation.APPLY_ASYNC_SUPPLY, true);
        TaskPropagation.asyncApplies(TaskPropagation.APPLY_ASYNC_RUN, true);
        assertThat(executors()).containsEntry("asyncApplies", true);

        TaskPropagation.reset();

        assertThat(executors()).containsEntry("asyncSupplyApplies", false).containsEntry("asyncRunApplies", false);
    }

    @Test
    void theQueueAndTheNewWorkerAreSeparateKeys() {
        TaskPropagation.beginSelfTest();
        TaskPropagation.submitted(new Object(), TaskPropagation.KEY_THREAD_POOL);
        TaskPropagation.submitted(new Object(), TaskPropagation.KEY_THREAD_POOL_QUEUE);
        TaskPropagation.submitted(new Object(), TaskPropagation.KEY_THREAD_POOL_QUEUE);

        assertThat(TaskPropagation.endSelfTest().get("keyed").toString())
                .contains("ThreadPoolExecutor.addWorker=1")
                .contains("ThreadPoolExecutor.queue=2");
    }

    /** A real {@code CompletableFuture$AsyncSupply}, captured before any thread runs it. */
    private static Runnable asyncSupply() {
        List<Runnable> captured = new ArrayList<>();
        CompletableFuture.supplyAsync(() -> "value", captured::add);
        return captured.get(0);
    }

    /** A real {@code CompletableFuture$AsyncRun}, captured before any thread runs it. */
    private static Runnable asyncRun() {
        List<Runnable> captured = new ArrayList<>();
        CompletableFuture.runAsync(() -> {}, captured::add);
        return captured.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> executors() {
        return (Map<String, Object>) AgentBridge.status().get("executors");
    }

    private long claimWith(List<String> skipTasks) {
        Object marker = new Object();
        capture = () -> marker == null ? null : owner.get();
        reopen = argument -> {
            Object[] call = (Object[]) argument;
            Object[] snapshot = (Object[]) call[0];
            reopened.add(snapshot[0] + " " + call[1] + " " + call[2]);
            return new Handle(failures, bodyFailures);
        };
        Map<String, Object> request = request(skipTasks);
        return (Long) AgentBridge.claim(request, capture, reopen).get("token");
    }

    private static Map<String, Object> request(List<String> skipTasks) {
        return request(skipTasks, List.of(TaskPropagation.SENSOR));
    }

    private static Map<String, Object> request(List<String> skipTasks, List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        request.put("executors", Map.of("skipTasks", skipTasks, "skipThreads", List.of("vert.x-")));
        return request;
    }

    private static Object[] snapshot(String requestId) {
        return new Object[] {requestId, null, null, null, null, null, null, 1L, 1L};
    }

    @SuppressWarnings("unchecked")
    private static long counter(String name) {
        Map<String, Object> executors =
                (Map<String, Object>) AgentBridge.status().get("executors");
        return (Long) executors.get(name);
    }

    static final class Handle implements AutoCloseable, Consumer<Throwable>, BiConsumer<Throwable, Boolean> {

        private final List<Throwable> failures;
        private final List<Boolean> bodyFailures;

        Handle(List<Throwable> failures, List<Boolean> bodyFailures) {
            this.failures = failures;
            this.bodyFailures = bodyFailures;
        }

        @Override
        public void accept(Throwable failure, Boolean bodyFailure) {
            bodyFailures.add(bodyFailure);
            accept(failure);
        }

        @Override
        public void accept(Throwable failure) {
            failures.add(failure);
        }

        @Override
        public void close() {}
    }
}
