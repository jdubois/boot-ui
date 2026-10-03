package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The executor sensor's behaviors in forked JVMs against the published jar (PLAN-v2 M5-2, D32): every executor behavior
 * of the M5-0 spike, outcomes, wrappers, Mockito, with the OpenTelemetry agent in both orders, and with two processors.
 */
class ExecutorBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");
    private static final String CLASS_PATH = String.join(
            File.pathSeparator,
            System.getProperty("opentelemetry.api.jar"),
            System.getProperty("opentelemetry.context.jar"),
            System.getProperty("opentelemetry.common.jar"),
            System.getProperty("mockito.jar"),
            System.getProperty("bytebuddy.jar"),
            System.getProperty("bytebuddy.agent.jar"),
            System.getProperty("objenesis.jar"));

    @Test
    void everyBehaviorPassesBesideMockito() throws Exception {
        assertAllPass(
                run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar"))));
    }

    @Test
    void everyBehaviorPassesBesideJacocoInstrumentingTheJdk() throws Exception {
        assertAllPass(run(List.of(
                "-javaagent:" + System.getProperty("jacoco.agent.jar") + "=destfile="
                        + ChildJvm.WORK.resolve("behaviors.exec")
                        + ",inclbootstrapclasses=true,includes=java.util.concurrent.*",
                ChildJvm.javaAgent(ChildJvm.AGENT))));
    }

    @Test
    void everyBehaviorPassesWithTwoProcessors() throws Exception {
        assertAllPass(run(List.of("-XX:ActiveProcessorCount=2", ChildJvm.javaAgent(ChildJvm.AGENT))));
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        ChildJvm.Output output = run(jvm);
        assertAllPass(output);
        assertThat(output.text()).contains("PASS in an OpenTelemetry span");
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        ChildJvm.Output output = run(jvm);
        assertAllPass(output);
        assertThat(output.text()).contains("PASS in an OpenTelemetry span");
    }

    private static ChildJvm.Output run(List<String> jvm) throws Exception {
        return ChildJvm.runWithClassPath(jvm, CLASS_PATH, "behaviors");
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text()).as(output.toString()).contains("SELF_TEST=").doesNotContain("SELF_TEST_FAILED");
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        assertThat(output.text()
                        .lines()
                        .filter(line -> line.startsWith("  PASS"))
                        .count())
                .as(output.toString())
                .isGreaterThanOrEqualTo(Runtime.version().feature() >= 21 ? 33 : 31);
        String fullStatus = output.value("STATUS");
        assertThat(fullStatus).as(output.toString()).contains("errors=0");
        // The executors sensor's counters only: the threads sensor reports its own.
        String status =
                fullStatus.substring(fullStatus.indexOf("executors={keyed"), fullStatus.indexOf("threads={keyed"));
        assertThat(status).as(output.toString()).contains("stale=0").contains("refused=0");
        assertThat(status)
                .as("owners sharing a task were told apart: %s", status)
                .doesNotContain("ambiguous=0,");
        if (Runtime.version().feature() >= 21) {
            assertThat(status)
                    .as("virtual-thread continuations were seen and skipped: %s", status)
                    .doesNotContain("virtualSkipped=0,");
        }
        java.util.regex.Matcher hooks = java.util.regex.Pattern.compile(
                        "id=([^,]+), kind=[a-z]+, type=[^,]+, present=(true|false), transformed=(true|false), selfTest=([^}]+)}")
                .matcher(output.value("SELF_TEST"));
        int count = 0;
        while (hooks.find()) {
            count++;
            String hook = hooks.group(1);
            String result = hooks.group(4);
            if (hook.equals("CompletableFuture.ThreadPerTaskExecutor")) {
                assertThat(result.startsWith("not-exercised") || result.equals("unsupported"))
                        .as("%s: %s", hook, result)
                        .isTrue();
            } else {
                assertThat(result)
                        .as("self-test of %s: %s", hook, output.value("SELF_TEST"))
                        .isIn("passed", "unsupported");
            }
        }
        assertThat(count).as("every hook reported").isEqualTo(10);
    }
}
