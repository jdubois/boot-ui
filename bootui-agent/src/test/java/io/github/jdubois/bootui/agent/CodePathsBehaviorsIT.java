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
 * The code-paths sensor's behaviors in forked JVMs against the published jar (PLAN-v2 M5-4a): a bean call chain under a
 * request is one fragment with its tree and phases, an exception path stays balanced, a handoff's fragment carries its
 * execution id, an ownerless call records nothing, the methods the sensor leaves alone are left alone, the depth cap
 * holds, a refine retransforms a loaded bean class, a later run's fresh class loader gets the advice at load, and a
 * release restores every class; alone, together with the inventory sensor on the same methods (both visits present,
 * each claim retransforming what its set of sensors changes), beside the OpenTelemetry agent in both orders, beside
 * JaCoCo, and with Mockito's inline mock maker in both orders.
 */
class CodePathsBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a bean call chain under a captured request yields one fragment with the right tree and phases",
            "an exception path stays balanced",
            "a handoff fragment carries the execution id",
            "no owner records nothing, capturing once per outermost call",
            "private, static, $-prefixed, Object, record accessor, constructor, configuration-properties, and non-bean"
                    + " methods are left alone",
            "calls past 32 levels stay in the level-32 node",
            "a refine naming a loaded bean class retransforms it",
            "a bean class a fresh class loader loads in a later run gets its advice as it loads",
            "release restores the classes");

    private static final List<String> REQUIRED_TOGETHER = List.of(
            "inventory and code-paths together: both visits on the same methods",
            "a claim dropping the inventory sensor removes its visit and keeps code-paths'",
            "a claim dropping the code-paths sensor removes its visit and keeps the inventory's");

    @Test
    void everyCodePathsBehaviorPassesAlone() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "code-paths"), false);
    }

    @Test
    void everyCodePathsBehaviorPassesWithTheInventorySensor() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "both"), true);
    }

    @Test
    void everyCodePathsBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "both"), true);
    }

    @Test
    void everyCodePathsBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "both"), true);
    }

    @Test
    void everyCodePathsBehaviorPassesBesideJacocoWhichStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("code-paths.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        assertAllPass(
                run(
                        List.of(
                                "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuicodepathsapp.*",
                                ChildJvm.javaAgent(ChildJvm.AGENT)),
                        "both"),
                true);

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData service = loader.getExecutionDataStore().getContents().stream()
                .filter(data -> data.getName().equals("bootuicodepathsapp/OrderService"))
                .findFirst()
                .orElseThrow();
        assertThat(service.hasHits())
                .as("JaCoCo recorded OrderService's coverage")
                .isTrue();
    }

    /**
     * Mockito's inline mock maker beside the code-paths sensor, in both transformer orders: a spy's real call is recorded
     * as a fragment, and the thread's depth is balanced after a stubbed call and after a spied one, whichever advice
     * runs first.
     */
    @Test
    void aSpysRealCallIsRecordedAndEveryCallStaysBalancedInBothTransformerOrders() throws Exception {
        for (String order : List.of("bootui-first", "mockito-first")) {
            String classPath = String.join(
                    java.io.File.pathSeparator,
                    System.getProperty("mockito.jar"),
                    System.getProperty("bytebuddy.jar"),
                    System.getProperty("bytebuddy.agent.jar"),
                    System.getProperty("objenesis.jar"));
            ChildJvm.Output output = ChildJvm.runWithClassPaths(
                    List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                    TestJars.codePathsClassPath(),
                    classPath,
                    "code-paths-mockito",
                    order);

            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("MOCKITO")).as(output.toString()).isEqualTo("ok");
            assertThat(output.value("SELF_TEST_code-paths"))
                    .as(output.toString())
                    .startsWith("true null");
            assertThat(output.value("MOCK_CLASS")).as(output.toString()).isEqualTo("bootuicodepathsapp.PriceClient");
            assertThat(output.value("STUBBED")).as(output.toString()).endsWith("depth 0");
            assertThat(output.value("SPY"))
                    .as(output.toString())
                    .contains("PriceClient#real")
                    .endsWith("depth 0");
            assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        }
    }

    @Test
    void aClassTheCodePathsVisitPushesPastTheMethodSizeLimitKeepsItsInventory() throws Exception {
        ChildJvm.Output output = ChildJvm.runWithClassPaths(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT)),
                TestJars.codePathsClassPath(),
                TestJars.tightJar().toString(),
                "code-paths-behaviors",
                "tight");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_code-paths")).as(output.toString()).startsWith("true null");
        assertThat(output.text().lines().filter(line -> line.startsWith("  PASS ")))
                .as(output.toString())
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .startsWith("  PASS a class the code-paths visit pushes past the 64 KB limit keeps its"
                                + " inventory visit"));
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    private static ChildJvm.Output run(List<String> jvm, String mode) throws Exception {
        return ChildJvm.runWithClassPaths(jvm, TestJars.codePathsClassPath(), null, "code-paths-behaviors", mode);
    }

    private static void assertAllPass(ChildJvm.Output output, boolean together) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_code-paths")).as(output.toString()).startsWith("true null");
        if (together) {
            assertThat(output.value("SELF_TEST_inventory"))
                    .as(output.toString())
                    .startsWith("true null");
        }
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        List<String> required = new ArrayList<>(REQUIRED);
        if (together) {
            required.addAll(REQUIRED_TOGETHER);
        }
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        assertThat(output.value("CODE_PATHS")).as(output.toString()).contains("off=false");
        // The shared transformer's counters are on the inventory's row whenever its visit applies.
        assertThat(output.value(together ? "SENSOR_inventory" : "SENSOR"))
                .as(output.toString())
                .contains("failed=0")
                .contains("skipped=0");
    }
}
