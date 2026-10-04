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
 * An opt-in, JMH-free measurement of the code-paths advice (PLAN-v2 M5-4a, target under 60 ns per instrumented call):
 * one {@code enter} and one {@code exit} per call, inside a recording fragment, without an owner, and for an excluded
 * method. Run with {@code -Dbootui.agent.bench=true}; it prints nanoseconds per call and asserts only that the work
 * happened.
 */
class CodePathsBenchmarkTests {

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
    void enterAndExit() {
        int[] ids = new int[16];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = CodeInventory.methodId("com.example.Bench#m" + i + "()V");
        }
        Object[] owned = {"00000000000000ab", null, null, null, "/bench"};
        Object[][] context = {owned};
        long token = claim(() -> context[0]);
        List<long[]> blobs = new ArrayList<>();

        // A request: one outermost call with 200 calls three levels deep under it, merged into 1 + 16 * 3 nodes.
        double recording = measure(ids, () -> CodePaths.drain(token, blobs::add));
        context[0] = null;
        double unowned = measure(ids, () -> {});
        context[0] = owned;
        for (int id : ids) {
            CodePaths.exclude(token, id);
        }
        double excluded = measure(ids, () -> {});

        long clock = 0;
        long started = System.nanoTime();
        for (int i = 0; i < 10_000_000; i++) {
            clock += System.nanoTime();
        }
        double nanoTime = (double) (System.nanoTime() - started) / 10_000_000;
        System.out.printf(Locale.ROOT, "BENCH System.nanoTime(): %.1f ns per read (%d)%n", nanoTime, clock & 1);
        System.out.printf(Locale.ROOT, "BENCH code-paths recording: %.1f ns per instrumented call%n", recording);
        System.out.printf(Locale.ROOT, "BENCH code-paths without an owner: %.1f ns per instrumented call%n", unowned);
        System.out.printf(Locale.ROOT, "BENCH code-paths excluded: %.1f ns per instrumented call%n", excluded);
        assertThat(blobs).isNotEmpty();
        assertThat(blobs.get(0)[CodePaths.H_NODES]).isEqualTo(1 + 16 * 3);
    }

    /** The best of 25 rounds, in nanoseconds per instrumented call, each round 2,000 requests of 201 calls. */
    private static double measure(int[] ids, Runnable afterRequest) {
        long best = Long.MAX_VALUE;
        int requests = 2_000;
        for (int round = 0; round < 25; round++) {
            long started = System.nanoTime();
            for (int request = 0; request < requests; request++) {
                int outer = CodePaths.enter(ids[0]);
                for (int call = 0; call < 200; call += 3) {
                    int a = CodePaths.enter(ids[(call) & 15]);
                    int b = CodePaths.enter(ids[(call + 1) & 15]);
                    int c = CodePaths.enter(ids[(call + 2) & 15]);
                    CodePaths.exit(c);
                    CodePaths.exit(b);
                    CodePaths.exit(a);
                }
                CodePaths.exit(outer);
                if ((request & 63) == 0) {
                    afterRequest.run();
                }
            }
            afterRequest.run();
            best = Math.min(best, System.nanoTime() - started);
        }
        return (double) best / (requests * 202L);
    }

    private long claim(Supplier<Object> capture) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "bench");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of(CodePaths.SENSOR));
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }
}
