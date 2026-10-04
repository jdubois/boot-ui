package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import io.github.jdubois.bootui.agent.bridge.ThreadPropagation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each hook's self-test verdict comes from its own count, never from a sibling's (PLAN-v2 M5-1, M5-2). */
class SelfTestEvaluationTests {

    @BeforeEach
    @AfterEach
    void resetThreadPropagation() throws Exception {
        var reset = ThreadPropagation.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void aThreadPoolKeyOrAsyncApplyHookThatSawNothingFailsBesideAPassingSibling() {
        Map<String, Object> keyed = new LinkedHashMap<>();
        keyed.put("ThreadPoolExecutor.addWorker", 1L);
        keyed.put("ThreadPoolExecutor.queue", 0L);
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("ThreadPoolExecutor.runWorker", 2L);
        applied.put("CompletableFuture.AsyncSupply", 1L);
        applied.put("CompletableFuture.AsyncRun", 0L);

        Map<String, String> results = ExecutorSensor.evaluate(
                Map.of("keyed", keyed, "applied", applied), Map.of("thread-pool", "ok", "fork-join", "ok"));

        assertThat(results)
                .containsEntry("ThreadPoolExecutor.addWorker", "passed")
                .containsEntry("ThreadPoolExecutor.queue", "failed")
                .containsEntry("ThreadPoolExecutor.runWorker", "passed")
                .containsEntry("CompletableFuture.AsyncSupply", "passed")
                .containsEntry("CompletableFuture.AsyncRun", "failed");
    }

    @Test
    void aVirtualThreadRunThatSawNothingFailsBesideAPassingPlatformRun() {
        Map<String, String> results = ThreadSensor.evaluate(
                Map.of(
                        "keyed", Map.of("Thread.start", 1L, "VirtualThread.start", 1L),
                        "applied", Map.of("Thread.run", 1L, "VirtualThread.run", 0L)),
                Map.of("platform", "ok", "virtual", "ok"));

        assertThat(results).containsEntry("Thread.run", "passed");
        assertThat(results.get("VirtualThread.run"))
                .isEqualTo(ExecutorSensor.present("java.lang.VirtualThread") ? "failed" : "unsupported");
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "interrupted", "error: probe failed"})
    void anUnsuccessfulExecutorStepWithoutCoreHitsDisablesPropagation(String outcome) {
        ExecutorSensor sensor = new ExecutorSensor(null, false);
        Map<String, String> steps = Map.of("thread-pool", outcome, "fork-join", "ok");
        Map<String, String> results =
                ExecutorSensor.evaluate(Map.of("keyed", Map.of(), "applied", Map.of("ForkJoinTask.doExec", 1L)), steps);
        try {
            sensor.recordSelfTest(42L, results, steps);

            assertThat(sensor.status()).containsEntry("selfTestPassed", false).containsEntry("selfTestSteps", steps);
            assertThat(sensor.status().get("selfTestError")).asString().contains("ThreadPoolExecutor");
            assertThat(((Map<?, ?>) AgentBridge.status().get("executors")).get("disabledReason"))
                    .asString()
                    .contains("self-test failed for", outcome);
        } finally {
            TaskPropagation.enable();
            ExecutorSensor.unverifyAsync();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "interrupted", "error: probe failed"})
    void anUnsuccessfulPlatformStepWithoutHitsDisablesTheThreadsSensor(String outcome) throws Exception {
        ThreadSensor sensor = new ThreadSensor(null, false);
        Map<String, String> steps = Map.of("platform", outcome, "virtual", "unsupported");
        Map<String, String> results = ThreadSensor.evaluate(Map.of("keyed", Map.of(), "applied", Map.of()), steps);

        sensor.recordSelfTest(42L, results, steps);

        assertThat(sensor.status())
                .containsEntry("selfTestPassed", false)
                .containsEntry("selfTestSteps", steps)
                .containsEntry("state", "self-test-failed");
        assertThat(sensor.status().get("selfTestError")).asString().contains("Thread.start", "Thread.run", outcome);
        var disabled = ThreadPropagation.class.getDeclaredMethod("disabled", long.class);
        disabled.setAccessible(true);
        assertThat(disabled.invoke(null, 42L)).isEqualTo(true);
        assertThat(disabled.invoke(null, 43L)).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"not-exercised (timeout)", "not-exercised (interrupted)", "not-exercised (error: probe failed)"})
    void eachUnverifiedCoreExecutorHookFailsEvenWhenItsSiblingsPass(String verdict) {
        for (String core : ExecutorSensor.CORE) {
            ExecutorSensor sensor = new ExecutorSensor(null, false);
            Map<String, String> results = new LinkedHashMap<>();
            for (String sibling : ExecutorSensor.CORE) {
                results.put(sibling, "passed");
            }
            results.put(core, verdict);
            try {
                sensor.recordSelfTest(42L, results, Map.of());

                assertThat(sensor.status()).containsEntry("selfTestPassed", false);
                assertThat(sensor.status().get("selfTestError")).asString().contains("[" + core + "]");
            } finally {
                TaskPropagation.enable();
                ExecutorSensor.unverifyAsync();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"not-exercised (timeout)", "not-exercised (interrupted)", "not-exercised (error: probe failed)"})
    void eachUnverifiedPresentThreadHookFailsEvenWhenItsSiblingsPass(String verdict) {
        for (String[] hook : ThreadSensor.HOOKS) {
            if (!ExecutorSensor.present(hook[1])) {
                continue;
            }
            Map<String, String> results = new LinkedHashMap<>();
            for (String[] sibling : ThreadSensor.HOOKS) {
                results.put(sibling[0], ExecutorSensor.present(sibling[1]) ? "passed" : "unsupported");
            }
            results.put(hook[0], verdict);
            ThreadSensor sensor = new ThreadSensor(null, false);

            sensor.recordSelfTest(42L, results, Map.of());

            assertThat(sensor.status()).containsEntry("selfTestPassed", false);
            assertThat(sensor.status().get("selfTestError")).asString().contains("[" + hook[0] + "]");
        }
    }

    @Test
    void positiveHookHitsVerifyTheSensorsEvenWhenAnotherPartOfTheStepDidNotFinish() {
        Map<String, String> steps = Map.of("thread-pool", "timeout", "fork-join", "interrupted");
        ExecutorSensor executors = new ExecutorSensor(null, false);
        ThreadSensor threads = new ThreadSensor(null, false);
        Map<String, Object> seen = Map.of(
                "keyed",
                Map.of("ThreadPoolExecutor.addWorker", 1L, "ThreadPoolExecutor.queue", 1L),
                "applied",
                Map.of("ThreadPoolExecutor.runWorker", 1L, "ForkJoinTask.doExec", 1L));
        try {
            executors.recordSelfTest(Long.MAX_VALUE, ExecutorSensor.evaluate(seen, steps), steps);
            assertThat(executors.status()).containsEntry("selfTestPassed", true).containsEntry("state", "installed");

            Map<String, String> threadResults = ThreadSensor.evaluate(
                    Map.of(
                            "keyed", Map.of("Thread.start", 1L, "VirtualThread.start", 1L),
                            "applied", Map.of("Thread.run", 1L, "VirtualThread.run", 1L)),
                    Map.of("platform", "timeout", "virtual", "interrupted"));
            threads.recordSelfTest(43L, threadResults, Map.of());
            assertThat(threads.status()).containsEntry("selfTestPassed", true).containsEntry("state", "installed");
            assertThat(threadResults).containsEntry("Thread subclass run", "not-exercised");
        } finally {
            TaskPropagation.enable();
            ExecutorSensor.unverifyAsync();
        }
    }

    @Test
    void retestingClearsThePreviousFailureAndReadinessBeforePublishingTesting() {
        ExecutorSensor executors = new ExecutorSensor(null, false);
        ThreadSensor threads = new ThreadSensor(null, false);
        try {
            executors.recordSelfTest(Long.MAX_VALUE, Map.of(), Map.of());
            threads.recordSelfTest(42L, Map.of(), Map.of());

            executors.beginSelfTest();
            threads.beginSelfTest();

            assertThat(executors.status())
                    .containsEntry("state", "testing")
                    .containsEntry("selfTestPassed", false)
                    .containsEntry("selfTestError", null);
            assertThat(threads.status())
                    .containsEntry("state", "testing")
                    .containsEntry("selfTestPassed", false)
                    .containsEntry("selfTestError", null);
        } finally {
            TaskPropagation.enable();
            ExecutorSensor.unverifyAsync();
        }
    }

    @Test
    void unavailableVirtualThreadFeaturesDoNotFailVerifiedPlatformHooks() {
        String virtual = ExecutorSensor.step(
                seconds -> {
                    throw new UnsupportedOperationException("preview is disabled");
                },
                1);
        assertThat(virtual).isEqualTo("unsupported");
        Map<String, String> steps = Map.of("platform", "ok", "virtual", virtual);
        Map<String, String> results = ThreadSensor.evaluate(
                Map.of("keyed", Map.of("Thread.start", 1L), "applied", Map.of("Thread.run", 1L)), steps);
        assertThat(results)
                .containsEntry("VirtualThread.start", "unsupported")
                .containsEntry("VirtualThread.run", "unsupported");
        ThreadSensor sensor = new ThreadSensor(null, false);

        sensor.recordSelfTest(42L, results, steps);

        assertThat(sensor.status()).containsEntry("selfTestPassed", true);
    }
}
