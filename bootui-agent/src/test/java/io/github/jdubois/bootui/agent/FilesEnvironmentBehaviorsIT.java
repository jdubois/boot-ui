package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The files and environment sensors in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5d): first the JDK
 * retransformation check of each sensor's hooks alone and together ({@code FileInputStream}, {@code FileOutputStream},
 * and {@code RandomAccessFile}'s private {@code open}, the {@code Files} methods, {@code FileChannel.open}, {@code
 * System.getenv}, and {@code System.getProperty}, all loaded before the claim, transformed, and passing their
 * self-test), run on every JDK the agent's suite runs on; then their behaviors, alone, after and before the
 * OpenTelemetry agent, and beside JaCoCo; and {@code System.getProperty}'s cost with the environment sensor recording.
 */
class FilesEnvironmentBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> FILE_HOOKS = List.of(
            "FileInputStream.open",
            "FileOutputStream.open",
            "RandomAccessFile.open",
            "Files.newByteChannel",
            "Files.newInputStream",
            "Files.newOutputStream",
            "Files.delete",
            "Files.deleteIfExists",
            "Files.move",
            "Files.copy",
            "FileChannel.open");

    private static final List<String> ENVIRONMENT_HOOKS =
            List.of("System.getenv", "System.getenvAll", "System.getProperty");

    private static final List<String> REQUIRED = List.of(
            "a report written outside the temporary directory records its pattern, a write, under its request",
            "a temporary file's pattern starts with $TMPDIR",
            "every files hook records its kind and pattern, and a failed open its failure",
            "a file operation nested in another records once",
            "inside a scope, operations aggregate under the scope's owner and flush at its end",
            "class files, archives, and Java's home are counted in buckets, never recorded or interned",
            "a resource a class loader reads is class loading, counted and never recorded or interned",
            "a JDK logging handler's file is JDK logging",
            "a logging framework's file names the framework as its first frame outside the JDK",
            "a property read records its name once per owner, never its value or default",
            "an environment variable records its name, and getenv() every variable, never a value",
            "the JDK's own property reads are not recorded",
            "BootUI's own work is never recorded",
            "unowned work names its thread",
            "release restores the file and System classes");

    @ParameterizedTest
    @ValueSource(strings = {"check-files", "check-environment", "check"})
    void eachSensorsHooksRetransformTheirJdkClassesAndPassTheirSelfTest(String mode) throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "files-environment-behaviors", mode);

        assertThat(output.exitCode()).as(output.toString()).isZero();
        if (!mode.equals("check-environment")) {
            assertHooks(output, "files", FILE_HOOKS);
        }
        if (!mode.equals("check-files")) {
            assertHooks(output, "environment", ENVIRONMENT_HOOKS);
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyBehaviorPasses() throws Exception {
        assertAllPass(
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "files-environment-behaviors", "behaviors"));
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "files-environment-behaviors", "behaviors"));
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "files-environment-behaviors", "behaviors"));
    }

    @Test
    void everyBehaviorPassesBesideJacocoWhichStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("files-environment.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        assertAllPass(ChildJvm.run(
                List.of(
                        "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuiagentit.*",
                        ChildJvm.javaAgent(ChildJvm.AGENT)),
                "files-environment-behaviors",
                "behaviors"));

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData behaviors = loader.getExecutionDataStore().getContents().stream()
                .filter(data -> data.getName().equals("bootuiagentit/FilesEnvironmentBehaviors"))
                .findFirst()
                .orElseThrow();
        assertThat(behaviors.hasHits())
                .as("JaCoCo recorded the behaviors' coverage")
                .isTrue();
    }

    /**
     * {@code System.getProperty}'s cost with only the files sensor claimed (its class untouched) and with the
     * environment sensor recording, printed for the pull request and docs; asserted only against a loose ceiling, as
     * timings depend on the machine.
     */
    @Test
    void measuresGetPropertyWithTheEnvironmentSensorRecording() throws Exception {
        double off = nanos(ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "files-environment-behaviors", "overhead-off"));
        double on = nanos(ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "files-environment-behaviors", "overhead-environment"));
        System.out.printf(
                java.util.Locale.ROOT,
                "System.getProperty: %.1f ns/call without the environment sensor, %.1f ns/call with it%n",
                off,
                on);
        assertThat(on)
                .as("getProperty with the environment sensor, against %.1f ns without", off)
                .isLessThan(off + 1_000);
    }

    private static double nanos(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        // The last of three rounds, after the JIT compiled the loop.
        return Double.parseDouble(output.value("GET_PROPERTY_NANOS_2").split(" ")[0]);
    }

    private static void assertHooks(ChildJvm.Output output, String sensor, List<String> hooks) {
        String selfTest = output.value("SELF_TEST_" + sensor);
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        for (String hook : hooks) {
            assertThat(selfTest).as("%s in %s", hook, output).contains("id=" + hook + ", kind=record");
        }
        assertThat(selfTest).as(output.toString()).doesNotContain("present=false");
        assertThat(selfTest).as(output.toString()).doesNotContain("transformed=false");
        assertThat(selfTest.split("selfTest=passed", -1).length - 1)
                .as(output.toString())
                .isEqualTo(hooks.size());
        assertThat(output.value("SENSOR_" + sensor)).as(output.toString()).contains("state=installed");
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_files")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SELF_TEST_environment")).as(output.toString()).startsWith("true null");
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
        assertThat(output.text())
                .as("no value ever printed by the bridge")
                .doesNotContain("hunter2-bootui-secret-value");
    }
}
