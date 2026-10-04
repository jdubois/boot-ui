package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An opt-in, JMH-free measurement of the inventory advice's fast path (PLAN-v2 M5-3): a call to a method that already
 * ran in this run, and a method's first call (flag, capture, and ring record). Run with
 * {@code -Dbootui.agent.bench=true}; it prints nanoseconds per call and asserts only that the work happened.
 */
class CodeInventoryBenchmarkTests {

    private final List<Object> keep = new ArrayList<>();

    @BeforeEach
    void install() {
        Assumptions.assumeTrue(Boolean.getBoolean("bootui.agent.bench"), "opt-in: -Dbootui.agent.bench=true");
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
    void fastPathAndFirstHit() {
        int methods = 200_000;
        int[] ids = new int[methods];
        for (int i = 0; i < methods; i++) {
            ids[i] = CodeInventory.methodId("com.example.Bench#m" + i + "()V");
        }
        Object[] payload = {"00000000000000ab", "exec", null, null, "/bench"};
        claim(() -> payload);

        long firstStarted = System.nanoTime();
        for (int i = 0; i < methods; i++) {
            advice(ids[i]);
        }
        long firstNanos = System.nanoTime() - firstStarted;

        long sink = 0;
        int rounds = 5;
        long best = Long.MAX_VALUE;
        long calls = 0;
        for (int round = 0; round < rounds; round++) {
            long started = System.nanoTime();
            for (int repeat = 0; repeat < 1000; repeat++) {
                for (int i = 0; i < 1000; i++) {
                    sink += advice(ids[i]) ? 1 : 0;
                }
            }
            long elapsed = System.nanoTime() - started;
            best = Math.min(best, elapsed);
            calls = 1_000_000L;
        }

        System.out.printf(
                java.util.Locale.ROOT,
                "BENCH first hit (flag, capture, ring record): %.1f ns/call over %d methods%n",
                (double) firstNanos / methods,
                methods);
        System.out.printf(
                java.util.Locale.ROOT,
                "BENCH already hit (fast path): %.2f ns/call, best of %d rounds of %d calls%n",
                (double) best / calls,
                rounds,
                calls);
        assertThat(sink).isZero();
        assertThat(CodeInventory.status()).containsEntry("firstHits", (long) methods);
    }

    private static boolean advice(int id) {
        if (CodeInventory.HITS[id] != CodeInventory.epoch) {
            CodeInventory.hit(id);
            return true;
        }
        return false;
    }

    private void claim(Supplier<Object> capture) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "bench");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of("inventory"));
        request.put("ringCapacity", 1 << 18);
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        assertThat(AgentBridge.claim(request, capture, reopen).get("status")).isEqualTo(AgentBridge.ARMED);
    }
}
