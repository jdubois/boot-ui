package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        assertThat(keyedOnly.get("keyed").toString()).contains("ThreadPoolExecutor=1");
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
    void asyncTasksAreLeftToTheirOwnAdvice() {
        assertThat(TaskPropagation.isAsyncTask(new Object())).isFalse();
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
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of(TaskPropagation.SENSOR));
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
