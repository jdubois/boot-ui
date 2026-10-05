package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The thread-locals sensor in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5f), on every JDK the agent's
 * suite runs on: the module grant and self-test (a scope finds exactly the plain, inheritable, and {@code withInitial}
 * thread locals it left set, and the resolver names a static holder); then its behaviors, alone and beside the
 * OpenTelemetry agent: a thread local a request left set is reported once with its request, never its value; one
 * cleared in {@code finally}, set to {@code null}, or set before the request never is; holders resolve to static fields;
 * a request's pool task is scanned on its worker; the JDK's own thread locals and virtual threads are skipped; and,
 * with the {@code Unsafe} initialization check forced off, the Code Inventory fallback still reports.
 */
class ThreadLocalsBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a thread local a request left set is reported once with its request, never its value",
            "thread locals cleared in finally, set to null, or set before the request are never reported",
            "holders are resolved to their static fields, a withInitial one flagged, an instance field's left"
                    + " unresolved",
            "a request's pool task leaving a thread local set on its worker is reported, one clearing it in finally"
                    + " never is",
            "the JDK's own thread locals, as a read lock's hold counter, are never reported",
            "no value set in a thread local ever reaches a string the bridge interned");

    private static final String VIRTUAL = "a virtual thread, never pooled, is never scanned";

    @Test
    void theModuleGrantAndTheSelfTestPassWithTheUnsafeInitializationCheck() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "thread-locals-behaviors", "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        String selfTest = output.value("SELF_TEST_thread-locals");
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        assertThat(selfTest)
                .as(output.toString())
                .contains("id=ThreadLocalMap.scan, kind=record, type=java.lang.ThreadLocal$ThreadLocalMap,"
                        + " present=true, transformed=true, selfTest=passed")
                .contains("id=ThreadLocal.holder, kind=record, type=java.lang.ThreadLocal, present=true,"
                        + " transformed=true, selfTest=passed");
        assertThat(output.value("SENSOR"))
                .as(output.toString())
                .contains("state=installed")
                .contains("initializationCheck=unsafe");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyThreadLocalsBehaviorPasses() throws Exception {
        assertAllPass(
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "thread-locals-behaviors", "behaviors"),
                REQUIRED);
    }

    @Test
    void everyThreadLocalsBehaviorPassesBesideOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "thread-locals-behaviors", "behaviors"), REQUIRED);
    }

    /** The Unsafe check forced off, as on a JDK without the method or the export: the Code Inventory fallback. */
    @Test
    void withoutTheUnsafeCheckTheResolverFallsBackToCodeInventoryAndTheSensorStillReports() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(
                        ChildJvm.javaAgent(ChildJvm.TEST_AGENT),
                        "-Dbootui.agent.it.omit=" + ThreadLocalsSensor.UNSAFE_CHECK),
                "thread-locals-behaviors",
                "fallback");

        List<String> required = new ArrayList<>(REQUIRED);
        required.remove(2);
        required.add("without the Unsafe check the resolver falls back to Code Inventory and leftovers are still"
                + " reported");
        assertAllPass(output, required);
        assertThat(output.value("SELF_TEST_thread-locals"))
                .as(output.toString())
                .contains("id=ThreadLocal.holder, kind=record, type=java.lang.ThreadLocal, present=true,"
                        + " transformed=true, selfTest=passed");
        assertThat(output.value("SENSOR")).as(output.toString()).contains("initializationCheck=inventory");
    }

    private static void assertAllPass(ChildJvm.Output output, List<String> required) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_thread-locals"))
                .as(output.toString())
                .startsWith("true null");
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        List<String> all = new ArrayList<>(required);
        if (Runtime.version().feature() >= 21) {
            all.add(VIRTUAL);
        }
        for (String behavior : all) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        assertThat(output.text()).as("no value ever printed by the bridge").doesNotContain("tenant-secret-value-42");
    }
}
