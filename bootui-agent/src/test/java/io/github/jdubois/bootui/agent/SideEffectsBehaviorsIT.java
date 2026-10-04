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
 * The side-effect sensors in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5a): first the JDK
 * retransformation check of the {@code processes} hook alone ({@code ProcessBuilder.start(Redirect[])}, loaded before
 * the claim, transformed, and passing its self-test), run on every JDK the agent's suite runs on; then its behaviors: a
 * started process records its command's file name and its exit, never an argument or the environment, through
 * {@code ProcessBuilder.start}, {@code Runtime.exec}, and {@code startPipeline}; a failed start records its failure;
 * unowned work names its thread; BootUI's own work is skipped; a release restores the class; beside the OpenTelemetry
 * agent in both orders, beside JaCoCo, and with Mockito's inline mock maker mocking {@code ProcessBuilder} in both
 * orders.
 */
class SideEffectsBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a started process records its command's file name and its exit, never an argument or the environment",
            "Runtime.exec records through ProcessBuilder",
            "ProcessBuilder.startPipeline records each process",
            "a command that cannot start records its failure",
            "unowned work names its thread",
            "BootUI's own work is never recorded",
            "release restores ProcessBuilder");

    @Test
    void theProcessesHookRetransformsProcessBuilderAndPassesItsSelfTest() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "side-effects-behaviors", "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_processes"))
                .as(output.toString())
                .startsWith("true null")
                .contains("id=ProcessBuilder.start")
                .contains("present=true")
                .contains("transformed=true")
                .contains("selfTest=passed");
        assertThat(output.value("SENSOR"))
                .as(output.toString())
                .contains("state=installed")
                .contains("failed=0")
                .contains("skipped=0");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyProcessesBehaviorPasses() throws Exception {
        assertAllPass(ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "side-effects-behaviors", "behaviors"));
    }

    @Test
    void everyProcessesBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "side-effects-behaviors", "behaviors"));
    }

    @Test
    void everyProcessesBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "side-effects-behaviors", "behaviors"));
    }

    @Test
    void everyProcessesBehaviorPassesBesideJacocoWhichStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("side-effects.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        assertAllPass(ChildJvm.run(
                List.of(
                        "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuiagentit.*",
                        ChildJvm.javaAgent(ChildJvm.AGENT)),
                "side-effects-behaviors",
                "behaviors"));

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData behaviors = loader.getExecutionDataStore().getContents().stream()
                .filter(data -> data.getName().equals("bootuiagentit/SideEffectsBehaviors"))
                .findFirst()
                .orElseThrow();
        assertThat(behaviors.hasHits())
                .as("JaCoCo recorded the behaviors' coverage")
                .isTrue();
    }

    /**
     * Mockito's inline mock maker retransforms {@code ProcessBuilder} itself to mock it, before or after the claim: the
     * stubbed call never reaches the hook, and a real {@code ProcessBuilder} still records.
     */
    @Test
    void aMockedProcessBuilderAndARealOneCoexistInBothTransformerOrders() throws Exception {
        for (String order : List.of("bootui-first", "mockito-first")) {
            String classPath = String.join(
                    java.io.File.pathSeparator,
                    System.getProperty("mockito.jar"),
                    System.getProperty("bytebuddy.jar"),
                    System.getProperty("bytebuddy.agent.jar"),
                    System.getProperty("objenesis.jar"));
            ChildJvm.Output output = ChildJvm.runWithClassPath(
                    List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                    classPath,
                    "side-effects-behaviors",
                    order);

            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("SELF_TEST_processes"))
                    .as(output.toString())
                    .startsWith("true null");
            assertThat(output.value("MOCKITO")).as(output.toString()).isEqualTo("ok");
            assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                    .as(output.toString())
                    .isEmpty();
            assertThat(output.text())
                    .as(output.toString())
                    .contains("  PASS a real ProcessBuilder beside a Mockito mock still records");
            assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        }
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_processes")).as(output.toString()).startsWith("true null");
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
        assertThat(output.value("PROCESSES")).as(output.toString()).contains("off=false");
        assertThat(output.text()).as("no secret ever printed by the bridge").doesNotContain("BOOTUI_SECRET_VARIABLE=");
    }
}
