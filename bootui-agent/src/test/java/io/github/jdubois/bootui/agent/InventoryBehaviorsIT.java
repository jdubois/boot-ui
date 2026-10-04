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
 * The inventory sensor's behaviors in forked JVMs against the published jar (PLAN-v2 M5-3): executed methods flip once
 * per run with the first request and route, class loads are counted per code source, BootUI's own work and re-entrant
 * captures record nothing they should not, exclusions hold, delegating constructors, records, and enums are
 * instrumented, a class too large for the advice is tracked as failed, classes instrumented after they loaded are late,
 * refined packages stay instrumented across narrower claims and fresh class loaders, and a release restores every class;
 * alone, beside the OpenTelemetry agent in both orders, beside JaCoCo, and with Mockito's inline mock maker in both
 * orders.
 */
class InventoryBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final Path JACOCO = Path.of(System.getProperty("jacoco.agent.jar"));
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a method called before the claim is not executed yet",
            "a method called before the claim then after is executed after",
            "a class instrumented after it loaded is late for that run",
            "a first call without a request sets its flag",
            "a method flips exactly once under concurrent first calls",
            "a first hit carries its request id and route",
            "a first call without a request records nothing",
            "constructors are instrumented",
            "default interface methods are instrumented",
            "abstract interface methods are not",
            "a never-called method is tracked and stays unset",
            "a class is tracked in the run it was instrumented in",
            "static initializers are not instrumented",
            "methods whose names start with $ are not instrumented",
            "a class loaded from a test root is not instrumented",
            "constructors delegating to super(args) and this(...) are instrumented and behave",
            "a record's constructor, accessors, and object methods are instrumented and behave",
            "a class loaded after the install is not late",
            "an enum's constructor, values, and valueOf are instrumented and behave, its $values is not",
            "a class that fails to transform still runs, and its methods are tracked as failed, never executed",
            "a jar whose class loads is counted",
            "a jar's first class load carries its request",
            "a jar no class loads from is absent",
            "classes BootUI's own work loads are not counted",
            "a capture that loads a class and runs an instrumented method never recurses",
            "a new claim generation resets executed",
            "a class instrumented before its run started is not late in it",
            "a class instrumented in an earlier run is not tracked in a new run until it is instrumented again",
            "a class a fresh class loader loads in a new run is tracked in it",
            "a class that fails to transform is named as failed, and not tracked in its run",
            "a refine instruments the loaded classes of the package it adds, late",
            "the same class in a class loader created after a narrower claim keeps its method ids and flips them",
            "after a narrower claim and its refine, a refined package's classes in every class loader are instrumented",
            "release restores the classes",
            "release restores the classes of packages refined before a narrower claim, in every class loader",
            "a claim without the inventory sensor removes its advice");

    @Test
    void everyInventoryBehaviorPasses() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT))));
    }

    @Test
    void anOldObjectCannotExecuteItsChangedReplacementAfterReloadEvenWhenRetransformed() throws Exception {
        assertReload("tracked");
    }

    @Test
    void previouslyUntrackedOldLoadersStayIneligibleWhenInventoryStartsAfterReload() throws Exception {
        assertReload("unseen");
    }

    @Test
    void equalButDistinctLoadersDoNotShareDefinitionEligibility() throws Exception {
        assertReload("equal");
    }

    @Test
    void anUnseenOldLoaderDefiningALazyClassBeforeItsFirstRetransformationStaysIneligible() throws Exception {
        assertReload("unseen-lazy");
    }

    private static void assertReload(String mode) throws Exception {
        String buddy = System.getProperty("bytebuddy.agent.jar");
        String packageName = "unseen-lazy".equals(mode) ? "bootuiinventoryhidden" : "bootuiinventoryapp";
        ChildJvm.Output output = ChildJvm.runWithClassPath(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + buddy),
                buddy,
                "inventory-reload",
                TestJars.inventoryReloadJar(1, packageName).toAbsolutePath().toString(),
                TestJars.inventoryReloadJar(2, packageName).toAbsolutePath().toString(),
                mode);
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("RELOAD")).as(output.toString()).isEqualTo("ok");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyInventoryBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm));
    }

    @Test
    void everyInventoryBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm));
    }

    @Test
    void everyInventoryBehaviorPassesBesideJacocoWhichStillWritesItsCoverage() throws Exception {
        Path exec = ChildJvm.WORK.resolve("inventory.exec");
        Files.createDirectories(ChildJvm.WORK);
        Files.deleteIfExists(exec);
        assertAllPass(run(List.of(
                "-javaagent:" + JACOCO + "=destfile=" + exec + ",includes=bootuiinventoryapp.*",
                ChildJvm.javaAgent(ChildJvm.AGENT))));

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(exec.toFile());
        ExecutionData app = loader.getExecutionDataStore().getContents().stream()
                .filter(data -> data.getName().equals("bootuiinventoryapp/App"))
                .findFirst()
                .orElseThrow();
        assertThat(app.hasHits()).as("JaCoCo recorded App's coverage").isTrue();
    }

    /**
     * Mockito's inline mock maker adds its own dispatch advice at the entry of a mocked type's methods, and a stubbed
     * call returns from it before the original body runs. In both orders (the inventory sensor installed before the
     * first mock, or a mock created before the claim) Mockito's dispatch ends up first: in the second order the sensor's
     * retransformation is handed the original bytes and Mockito's transformer wraps its output, as dumping both agents'
     * classes shows. So a stubbed call never counts as executed, which is what the inventory wants (it did not run the
     * method), and a spy's real call always does.
     */
    @Test
    void aStubbedCallIsNeverExecutedAndASpysRealCallIsInBothTransformerOrders() throws Exception {
        for (String order : List.of("bootui-first", "mockito-first")) {
            ChildJvm.Output output = mockito(order);

            assertThat(output.value("MOCK_CLASS")).as(output.toString()).isEqualTo("bootuiinventoryapp.Stubbed");
            assertThat(output.value("STUBBED_EXECUTED")).as(output.toString()).isEqualTo("false");
            assertThat(output.value("SPY_EXECUTED")).as(output.toString()).isEqualTo("true");
        }
    }

    private static ChildJvm.Output mockito(String order) throws Exception {
        String classPath = String.join(
                java.io.File.pathSeparator,
                System.getProperty("mockito.jar"),
                System.getProperty("bytebuddy.jar"),
                System.getProperty("bytebuddy.agent.jar"),
                System.getProperty("objenesis.jar"));
        ChildJvm.Output output = ChildJvm.runWithClassPaths(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                TestJars.inventoryClassPath(),
                classPath,
                "inventory-mockito",
                order);
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("MOCKITO")).as(output.toString()).isEqualTo("ok");
        assertThat(output.value("SELF_TEST_inventory")).as(output.toString()).startsWith("true null");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        return output;
    }

    private static ChildJvm.Output run(List<String> jvm) throws Exception {
        return ChildJvm.runWithClassPaths(jvm, TestJars.inventoryClassPath(), null, "inventory-behaviors");
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_inventory")).as(output.toString()).startsWith("true null");
        assertThat(output.text()).as("every refine was applied in time").doesNotContain("REFINE_TIMEOUT");
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
        // Huge fails at its first load, and again at the reinstall's retransformation after the release: nothing else.
        String sensor = output.value("SENSOR");
        assertThat(output.number("SENSOR", "failed")).as(output.toString()).isEqualTo(2);
        assertThat(sensor.split("bootuiinventoryapp\\.Huge: [^,\\]]*MethodTooLargeException", -1))
                .as("every failure is Huge's method too large for the advice: %s", output)
                .hasSize(3);
        assertThat(sensor).as(output.toString()).contains("skipped=0");
    }
}
