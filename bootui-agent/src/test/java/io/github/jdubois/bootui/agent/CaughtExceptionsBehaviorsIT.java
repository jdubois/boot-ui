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
 * The caught-exceptions sensor's behaviors in forked JVMs against the published jar (PLAN-v2 M5-6a), on the JDK the
 * build runs (CI runs it on 17, 21, and the newest): handler entries and exceptional exits reported with their sites and
 * owners, on classes retransformed at the claim and on classes a fresh class loader defines afterwards, where the JVM
 * verifies the visit's frames as it defines them; alone, beneath the inventory's and the code paths' advice on the same
 * methods (which checks every frame), beside the OpenTelemetry agent in both orders, beside JaCoCo, with Mockito's
 * inline mock maker in both orders, and on a thread BlockHound watches.
 */
class CaughtExceptionsBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a swallowed exception is one caught record with its site, class, and request",
            "a rethrown exception is caught, then thrown from its method's exit",
            "a wrapped rethrow is found through the cause",
            "a rethrow by a library-style helper is seen at the method's exit",
            "an exception rethrown and caught again in its method is found by the outer handler",
            "a finally reports nothing, a multi-catch one site",
            "a site caught often publishes its first occurrences, then counts the rest",
            "a constructor's and a lambda's handlers are reported",
            "without an owner nothing is recorded",
            "transformed methods answer as before",
            "a class a fresh class loader defines gets the visit as it loads, with the same site ids",
            "a claim switching the sensor off then on runs its self-test again before recording",
            "release restores the classes");

    @Test
    void everyBehaviorPassesAlone() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "alone"), false);
    }

    @Test
    void everyBehaviorPassesBeneathTheInventoryAndCodePathsAdviceOnTheSameMethods() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "all"), true);
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "all"), true);
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "all"), true);
    }

    @Test
    void everyBehaviorPassesBesideJacocoWhichStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("caught-exceptions.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        assertAllPass(
                run(
                        List.of(
                                "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuicaughtapp.*",
                                ChildJvm.javaAgent(ChildJvm.AGENT)),
                        "all"),
                true);

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData handlers = loader.getExecutionDataStore().getContents().stream()
                .filter(data -> data.getName().equals("bootuicaughtapp/Handlers"))
                .findFirst()
                .orElseThrow();
        assertThat(handlers.hasHits()).as("JaCoCo recorded Handlers' coverage").isTrue();
    }

    @Test
    void everyBehaviorPassesOnAThreadBlockHoundWatches() throws Exception {
        ChildJvm.Output output = ChildJvm.runWithClassPaths(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.AGENT),
                        "-XX:+AllowRedefinitionToAddDeleteMethods",
                        "-Dcaught.app.jar=" + appJar()),
                appJar().toString(),
                System.getProperty("blockhound.jar"),
                "caught-exceptions-behaviors",
                "blockhound");

        assertAllPass(output, false);
        assertThat(output.text())
                .as(output.toString())
                .contains("  PASS BlockHound watches the behaviors' thread")
                .contains("  PASS the behaviors ran on a thread BlockHound watches");
    }

    /** A spy's real call is reported and stubbing still works, whichever of Mockito and the claim comes first. */
    @Test
    void aSpysRealCallIsReportedInBothTransformerOrders() throws Exception {
        for (String order : List.of("bootui-first", "mockito-first")) {
            String classPath = String.join(
                    java.io.File.pathSeparator,
                    System.getProperty("mockito.jar"),
                    System.getProperty("bytebuddy.jar"),
                    System.getProperty("bytebuddy.agent.jar"),
                    System.getProperty("objenesis.jar"));
            ChildJvm.Output output = ChildJvm.runWithClassPaths(
                    List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                    appJar().toString(),
                    classPath,
                    "caught-exceptions-behaviors",
                    "mockito-" + order);

            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("SELF_TEST_caught-exceptions")).as(output.toString()).startsWith("true null");
            assertThat(output.value("MOCKITO")).as(output.toString()).isEqualTo("ok");
            assertThat(output.value("SPY"))
                    .as(output.toString())
                    .isEqualTo("[CAUGHT bootuicaughtapp/Handlers#swallowed()I#0#java/io/IOException"
                            + " java.io.IOException]");
            assertThat(output.value("CAUGHT")).as(output.toString()).contains("errors=0");
            assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        }
    }

    static Path appJar() throws Exception {
        return TestJars.jar("caught-app.jar", "bootuicaughtapp", List.of("bootuicaughtapp/ExitAdvice"));
    }

    private static ChildJvm.Output run(List<String> jvm, String mode) throws Exception {
        List<String> options = new ArrayList<>(jvm);
        options.add("-Dcaught.app.jar=" + appJar());
        return ChildJvm.runWithClassPaths(
                options, appJar().toString(), null, "caught-exceptions-behaviors", mode);
    }

    private static void assertAllPass(ChildJvm.Output output, boolean together) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_caught-exceptions")).as(output.toString()).startsWith("true null");
        if (together) {
            assertThat(output.value("SELF_TEST_code-paths")).as(output.toString()).startsWith("true null");
            assertThat(output.value("SELF_TEST_inventory")).as(output.toString()).startsWith("true null");
        }
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
        assertThat(output.value("CAUGHT")).as(output.toString()).contains("errors=0");
        // No class of the claimed packages lost a visit: the frames passed the code paths' advice and the JVM.
        assertThat(output.value("SENSOR"))
                .as(output.toString())
                .contains("rejectedTypes=0");
        if (together) {
            assertThat(output.value("SENSOR_inventory"))
                    .as(output.toString())
                    .contains("failed=0")
                    .contains("skipped=0");
        }
    }
}
