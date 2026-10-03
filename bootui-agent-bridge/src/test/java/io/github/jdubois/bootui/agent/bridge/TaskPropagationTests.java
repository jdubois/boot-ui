package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The executor sensor's bridge semantics (PLAN-v2 D32, M5-2), without the agent's advice: tasks keyed and applied. */
class TaskPropagationTests {

    private final ThreadLocal<Object> owner = new ThreadLocal<>();
    private final List<String> reopened = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
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
            return new Handle(failures);
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

    static final class Handle implements AutoCloseable, Consumer<Throwable> {

        private final List<Throwable> failures;

        Handle(List<Throwable> failures) {
            this.failures = failures;
        }

        @Override
        public void accept(Throwable failure) {
            failures.add(failure);
        }

        @Override
        public void close() {}
    }
}
