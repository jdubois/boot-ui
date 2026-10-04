package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Method probes in forked JVMs against the published jar (PLAN-v2 M5-8): a probe records its next 20 invocations and
 * removes itself, its window ends it without calls, an explicit stop ends it, an exception is recorded by its type, an
 * overloaded method needs its descriptor, a class loaded later is probed as it loads, five run at once and a sixth is
 * refused, a new claim generation and a release end and remove them, and after a restart a probe advises only the new
 * run's copy; the probed method keeps working and keeps the inventory and code-paths advice; alone, and beside the
 * OpenTelemetry agent in both orders.
 */
class MethodProbesIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a probe records its next 20 invocations, then removes itself, leaving both sensors' advice",
            "the window ends a probe without calls and removes it",
            "an explicit stop ends a probe at once and removes it",
            "an exception is recorded by its type, and the method still throws",
            "an overloaded method needs its descriptor; a unique one is resolved",
            "a class this run loads after its probe started is probed as it loads, another loader's copy never",
            "five probes run at once and a sixth is refused",
            "a new claim generation ends the previous run's probes and removes them",
            "after a restart, a probe advises only the new run's copy, its inventory stays tracked, and the next restart only"
                    + " deregisters it",
            "a call in flight across the install records nothing, one across the removal records once, and both sensors"
                    + " still see the method",
            "a release ends and removes probes");

    @Test
    void everyProbeBehaviorPasses() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT))));
    }

    @Test
    void everyProbeBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm));
    }

    @Test
    void everyProbeBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm));
    }

    private static ChildJvm.Output run(List<String> jvm) throws Exception {
        return ChildJvm.runWithClassPaths(jvm, TestJars.codePathsClassPath(), null, "probe-behaviors");
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_code-paths")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SELF_TEST_inventory")).as(output.toString()).startsWith("true null");
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
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        assertThat(output.value("PROBES"))
                .as(output.toString())
                .contains("inUse=0")
                .contains("reaped=0");
        assertThat(output.value("AGENT_PROBES"))
                .as(output.toString())
                .contains("removalFailures=0")
                .contains("skipped=0");
    }
}
