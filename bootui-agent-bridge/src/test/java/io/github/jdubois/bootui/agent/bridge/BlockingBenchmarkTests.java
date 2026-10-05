package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An opt-in, JMH-free measurement of the blocking sensor's {@code LockSupport.park} hook off event loops (PLAN-v2
 * M5-5c, budget: at most 10 ns per park with event loops registered, 2 ns with the sensor off): the advice's entry and
 * exit pair, as inlined into {@code park}, on a thread that is not an event loop, in three states. Run with
 * {@code -Dbootui.agent.bench=true}; it prints nanoseconds per park and asserts the budget with a margin for noisy
 * machines.
 */
class BlockingBenchmarkTests {

    private static final int CALLS = 20_000_000;

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
    void parkOffEventLoops() throws Exception {
        double baseline = measure(false);
        double off = measure(true);
        claim();
        SideEffects.enable(SideEffects.MASK_BLOCKING);
        double noLoops = measure(true);
        for (int i = 0; i < 16; i++) {
            Thread loop = new Thread(Blocking::registerEventLoop, "reactor-http-nio-" + i);
            loop.start();
            loop.join();
            keep.add(loop);
        }
        assertThat(SideEffects.mask & SideEffects.MASK_LOOPS).isNotZero();
        double loops = measure(true);

        System.out.printf(Locale.ROOT, "BENCH empty loop: %.2f ns per iteration%n", baseline);
        System.out.printf(Locale.ROOT, "BENCH park hook, sensor off: %.2f ns per park%n", off - baseline);
        System.out.printf(Locale.ROOT, "BENCH park hook, on, no event loop: %.2f ns per park%n", noLoops - baseline);
        System.out.printf(
                Locale.ROOT, "BENCH park hook, on, 16 event loops, off them: %.2f ns per park%n", loops - baseline);
        assertThat(off - baseline).as("sensor off").isLessThan(2.0 * 3);
        assertThat(loops - baseline).as("off event loops").isLessThan(10.0 * 3);
    }

    /** The best of 15 rounds, in nanoseconds per iteration. */
    private static double measure(boolean hook) {
        long best = Long.MAX_VALUE;
        long sink = 0;
        for (int round = 0; round < 15; round++) {
            long started = System.nanoTime();
            if (hook) {
                for (int i = 0; i < CALLS; i++) {
                    long token = Blocking.parking();
                    sink += token;
                    Blocking.parked(token, null);
                }
            } else {
                for (int i = 0; i < CALLS; i++) {
                    sink += i & 1;
                }
            }
            best = Math.min(best, System.nanoTime() - started);
        }
        assertThat(sink).isNotNegative();
        return (double) best / CALLS;
    }

    private void claim() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "bench");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of(SideEffects.BLOCKING));
        Supplier<Object> capture = () -> null;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        assertThat(AgentBridge.claim(request, capture, reopen).get("status")).isEqualTo(AgentBridge.ARMED);
    }
}
