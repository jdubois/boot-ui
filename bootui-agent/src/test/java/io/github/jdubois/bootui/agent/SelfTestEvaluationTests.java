package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each hook's self-test verdict comes from its own count, never from a sibling's (PLAN-v2 M5-1, M5-2). */
class SelfTestEvaluationTests {

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
}
