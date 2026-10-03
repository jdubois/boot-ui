package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The threads sensor's behaviors in forked JVMs against the published jar (PLAN-v2 M5-2c): threads the application
 * starts, Thread subclasses, virtual threads, and structured subtasks propagate exactly once, scoped values intact;
 * threads a library or a framework starts, context-propagating wrappers, JDK threads overriding {@code run()}, and pool
 * workers never do; released sensors leave threads untouched; beside the OpenTelemetry agent in both orders.
 */
class ThreadBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    /** Preview features on JDK 25+, for {@code StructuredTaskScope}. */
    private static final List<String> PREVIEW = Runtime.version().feature() >= 25
            ? List.of("--enable-preview", "-Dbootui.agent.it.preview=true")
            : List.of();

    @Test
    void everyThreadBehaviorPasses() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(PREVIEW);
        assertAllPass(ChildJvm.run(jvm, "thread-behaviors"));
    }

    /** One CPU's worth of common pool: {@code CompletableFuture} falls back to a thread per task. */
    @Test
    void completableFuturesThreadPerTaskFallbackStaysWithTheExecutorsSensor() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(
                ChildJvm.javaAgent(ChildJvm.AGENT), "-Djava.util.concurrent.ForkJoinPool.common.parallelism=1"));
        jvm.addAll(PREVIEW);
        assertAllPass(
                ChildJvm.run(jvm, "thread-behaviors"),
                "CompletableFuture's thread-per-task fallback is left to the executors sensor");
    }

    @Test
    void everyThreadBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        jvm.addAll(PREVIEW);
        assertAllPass(ChildJvm.run(jvm, "thread-behaviors"));
    }

    @Test
    void everyThreadBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        jvm.addAll(PREVIEW);
        assertAllPass(ChildJvm.run(jvm, "thread-behaviors"));
    }

    private static void assertAllPass(ChildJvm.Output output, String... alsoRequired) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_threads")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SELF_TEST_executors")).as(output.toString()).startsWith("true null");
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        int feature = Runtime.version().feature();
        List<String> required = new ArrayList<>(List.of(
                "an application task's thread propagates",
                "an application Thread subclass propagates",
                "an application thread running a JDK task propagates",
                "a thread a library starts with an application task is not propagated",
                "a JDK thread overriding run() is never keyed",
                "a thread started from unowned work stays unowned",
                "a pool worker never inherits",
                "a failing start is left to the application",
                "a failing thread reports its failure",
                "released sensors leave threads untouched"));
        required.addAll(List.of(alsoRequired));
        if (feature >= 21) {
            required.addAll(List.of(
                    "a virtual thread propagates",
                    "a virtual-thread-per-task executor propagates",
                    "a framework's virtual-thread dispatch is not propagated",
                    "a context-propagating wrapper of a virtual-thread-per-task executor is left alone"));
        }
        if (feature >= 25) {
            required.addAll(List.of(
                    "scoped values still bind inside a propagated thread",
                    "a structured subtask inherits its scoped values and its request"));
        }
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        String status = output.value("STATUS");
        assertThat(status).as(output.toString()).contains("errors=0");
        String threads = status.substring(status.indexOf("threads={keyed"));
        assertThat(threads)
                .as("library threads and pool workers were seen and left alone, and no key is left: %s", threads)
                .doesNotContain("libraryThreadsSkipped=0,")
                .doesNotContain("poolWorkersSkipped=0,")
                .contains("pending=0,");
    }
}
