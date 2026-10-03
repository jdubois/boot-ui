package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;

/**
 * The agent beside the OpenTelemetry Java agent, in both orders, and beside JaCoCo (PLAN-v2 M5-0, M5-1 acceptance):
 * OpenTelemetry must actually start, the probe must install without a single failed transformation, and JaCoCo must
 * still write its coverage.
 */
class AgentCoexistenceIT {

    private static final Path OPENTELEMETRY = Path.of(System.getProperty("opentelemetry.agent.jar"));
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS = List.of(
            "-Dotel.traces.exporter=none",
            "-Dotel.metrics.exporter=none",
            "-Dotel.logs.exporter=none",
            "-Dotel.javaagent.debug=false");

    @Test
    void openTelemetryFirst() throws Exception {
        probeBeside(List.of(ChildJvm.javaAgent(OPENTELEMETRY), ChildJvm.javaAgent(ChildJvm.TEST_AGENT)));
    }

    @Test
    void bootUiFirst() throws Exception {
        probeBeside(List.of(ChildJvm.javaAgent(ChildJvm.TEST_AGENT), ChildJvm.javaAgent(OPENTELEMETRY)));
    }

    @Test
    void jacocoStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("coexistence.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuiagentit.*",
                        ChildJvm.javaAgent(ChildJvm.TEST_AGENT),
                        "-Dbootui.agent.it.probe=bootuiagentit.probed"),
                "probe");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(Long.parseLong(output.value("HITS"))).isPositive();
        assertThat(output.number("INSTALLER", "retransformed"))
                .as(output.toString())
                .isPositive();
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData greeter = loader.getExecutionDataStore()
                .get(loader.getExecutionDataStore().getContents().stream()
                        .filter(data -> data.getName().equals("bootuiagentit/probed/Greeter"))
                        .findFirst()
                        .orElseThrow()
                        .getId());
        assertThat(greeter.hasHits()).as("JaCoCo recorded Greeter's coverage").isTrue();
    }

    @Test
    void mockitosInlineMockMakerSpiesAProbedFinalClass() throws Exception {
        Path mockito = Path.of(System.getProperty("mockito.jar"));
        String classPath = String.join(
                java.io.File.pathSeparator,
                mockito.toString(),
                System.getProperty("bytebuddy.jar"),
                System.getProperty("bytebuddy.agent.jar"),
                System.getProperty("objenesis.jar"));
        ChildJvm.Output output = ChildJvm.runWithClassPath(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.TEST_AGENT),
                        "-javaagent:" + mockito,
                        "-Dbootui.agent.it.probe=bootuiagentit.probed"),
                classPath,
                "mockito");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("MOCKITO")).isEqualTo("ok");
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("INSTALLER"))
                .as(output.toString())
                .contains("failed=0")
                .contains("skipped=0");
    }

    private static void probeBeside(List<String> agents) throws Exception {
        List<String> jvm = new ArrayList<>(agents);
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        jvm.add("-Dbootui.agent.it.probe=bootuiagentit.probed");
        ChildJvm.Output output = ChildJvm.run(jvm, "probe");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text()).as("OpenTelemetry started").contains("opentelemetry-javaagent - version:");
        assertThat(output.value("CLAIM")).isEqualTo("armed");
        assertThat(Long.parseLong(output.value("HITS"))).as(output.toString()).isPositive();
        assertThat(output.value("INSTALLER"))
                .as(output.toString())
                .contains("failed=0")
                .contains("skipped=0");
    }
}
