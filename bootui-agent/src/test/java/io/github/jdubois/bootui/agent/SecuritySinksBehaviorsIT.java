package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The security-sinks sensor's request-value matching in forked JVMs against the published jar (PLAN-v2 §5.16, M5-6b1),
 * on every JDK the agent's suite runs on: it adds no hook of its own, so it runs through the {@code files} and {@code
 * processes} sensors' real JDK hooks, alone and after and before the OpenTelemetry agent, which instruments the same
 * JDK, and never keeps or prints a seeded value.
 */
class SecuritySinksBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a file opened with a request's value records the redacted pattern, in its files record too",
            "a process started with a request's value records the command and the argument's index only",
            "a path not holding the value records no sink",
            "a task the request handed off records no sink",
            "after the response, nothing is held or matched");

    @Test
    void everyBehaviorPasses() throws Exception {
        assertAllPass(ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "security-sinks-behaviors"));
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "security-sinks-behaviors"));
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "security-sinks-behaviors"));
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : REQUIRED) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("VALUES_KEPT")).as(output.toString()).isEqualTo("false");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }
}
