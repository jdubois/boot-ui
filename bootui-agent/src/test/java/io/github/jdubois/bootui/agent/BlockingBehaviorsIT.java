package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The blocking sensor in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5c): first the JDK retransformation
 * check of its hooks alone, run on every JDK the agent's suite runs on: {@code LockSupport}, loaded before the claim,
 * transformed and its park hook self-tested, and the {@code Thread.sleep} and {@code Object.wait} call sites of the
 * claimed classes rewritten and self-tested; then its behaviors: a sleep, a {@code TimeUnit.sleep}, a wait, a lambda's
 * sleep, and a contended lock started on a registered event loop are recorded with their loop, owner, duration, and call
 * site, the same off event loops never are, a short park is only counted, an interrupted sleep throws as before, and a
 * release restores both; beside the OpenTelemetry agent in both orders, and beside BlockHound, installed before or after
 * the claim, recording or throwing from inside the sleep.
 */
class BlockingBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    /**
     * BlockHound wraps the JDK's native methods, which needs this flag from JDK 13, and attaches itself, which JDK 21
     * warns about unless dynamic agent loading is enabled.
     */
    private static final List<String> BLOCKHOUND_OPTIONS = Runtime.version().feature() >= 21
            ? List.of(
                    "-XX:+AllowRedefinitionToAddDeleteMethods",
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djdk.attach.allowAttachSelf=true")
            : List.of("-XX:+AllowRedefinitionToAddDeleteMethods", "-Djdk.attach.allowAttachSelf=true");

    private static final List<String> REQUIRED = List.of(
            "a Thread.sleep on an event loop is recorded with its loop, owner, duration, and call site",
            "the same sleep on a worker thread is not recorded",
            "TimeUnit.sleep and Object.wait on an event loop are recorded",
            "a lambda's sleep on an event loop is recorded at the lambda",
            "a contended lock parks the event loop and is recorded; a short park is only counted",
            "a park off event loops is never recorded",
            "an interrupted sleep throws as before, through the substitute, and is recorded interrupted",
            "release restores LockSupport and the rewritten call sites");

    /** The application's classes, from a jar: the agent never rewrites a class loaded from a test root. */
    private static String application() throws java.io.IOException {
        return TestJars.jar("blocking-app.jar", "bootuiblockingapp", List.of()).toString();
    }

    private static ChildJvm.Output run(List<String> jvm, String extraClassPath, String mode) throws Exception {
        return ChildJvm.runWithClassPaths(jvm, application(), extraClassPath, "blocking-behaviors", mode);
    }

    @Test
    void theBlockingHooksRetransformLockSupportRewriteCallSitesAndPassTheirSelfTests() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), null, "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_blocking"))
                .as(output.toString())
                .startsWith("true null")
                .contains("id=LockSupport.park, kind=record, type=java.util.concurrent.locks.LockSupport, present=true,"
                        + " transformed=true, selfTest=passed")
                .contains("id=Thread.sleep call sites, kind=record, type=(claimed packages), present=true,"
                        + " transformed=true, selfTest=passed")
                .contains("id=Object.wait call sites, kind=record, type=(claimed packages), present=true,"
                        + " transformed=true, selfTest=passed");
        assertThat(output.value("CALL_SITES")).as(output.toString()).startsWith("true null");
        assertThat(output.value("SENSOR"))
                .as(output.toString())
                .contains("state=installed")
                .contains("failed=0")
                .contains("skipped=0");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyBlockingBehaviorPasses() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), null, "behaviors"));
    }

    @Test
    void everyBlockingBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, null, "behaviors"));
    }

    @Test
    void everyBlockingBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, null, "behaviors"));
    }

    /**
     * BlockHound, installed before the claim (recording, then throwing its default error from inside the sleep) and
     * after it: both see the same sleep on the event loop, its error reaches the caller unchanged, and a later sleep on
     * a loop is still recorded.
     */
    @Test
    void besideBlockHoundInEveryOrder() throws Exception {
        for (String mode : List.of("blockhound-first", "bootui-first", "blockhound-throwing")) {
            List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)));
            jvm.addAll(BLOCKHOUND_OPTIONS);
            ChildJvm.Output output = run(jvm, System.getProperty("blockhound.jar"), mode);

            assertThat(output.exitCode()).as(mode + " " + output).isZero();
            assertThat(output.value("BLOCKHOUND")).as(mode + " " + output).isEqualTo("installed");
            assertThat(output.value("SELF_TEST_blocking"))
                    .as(mode + " " + output)
                    .startsWith("true null");
            assertThat(output.value("CALL_SITES")).as(mode + " " + output).startsWith("true null");
            assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                    .as(mode + " " + output)
                    .isEmpty();
            assertThat(output.text())
                    .as(mode + " " + output)
                    .contains("  PASS BlockHound and the sensor both see a sleep and a park on an event loop");
            assertThat(output.value("STATUS")).as(mode + " " + output).contains("errors=0");
        }
    }

    /**
     * The blocking sensor's call-site visit switched off and on beside the inventory's, on the transformer they share:
     * the inventory keeps tracking the application's methods, with no failed class.
     */
    @Test
    void switchingTheBlockingSensorKeepsTheInventoryTracking() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), null, "switch");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text())
                .as(output.toString())
                .contains(
                        "  PASS switching the blocking sensor keeps the inventory tracking the application's methods");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    /**
     * The park hook's cost off event loops, with an event loop registered: the advised {@code park} against the JDK's
     * unadvised park primitive in the same JVM, per call, with the permit given so each returns at once. The median of
     * 41 alternating paired rounds, so a shared runner's load or JIT shifts single pairs, not the result.
     */
    @Test
    void theParkHookOffEventLoopsStaysWithinItsBudget() throws Exception {
        ChildJvm.Output output = run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"),
                null,
                "bench");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        double hooked = Double.parseDouble(output.value("PARK_NANOS_HOOKED"));
        double plain = Double.parseDouble(output.value("PARK_NANOS_PLAIN"));
        double added = Double.parseDouble(output.value("PARK_NANOS_ADDED"));
        System.out.printf(
                Locale.ROOT,
                "BENCH park off event loops: %.1f ns hooked, %.1f ns unadvised, %.1f ns added (median of pairs %s);"
                        + " released gap %s ns%n",
                hooked,
                plain,
                added,
                output.value("PARK_NANOS_ADDED_PAIRS"),
                output.value("PARK_NANOS_RELEASED_GAP"));
        assertThat(added).as(output.toString()).isLessThan(50.0);
        // The control must stay a fair one: unadvised, the two arms cost alike, and the hook never reads as a saving.
        assertThat(added).as(output.toString()).isGreaterThan(-25.0);
        assertThat(Math.abs(Double.parseDouble(output.value("PARK_NANOS_RELEASED_GAP"))))
                .as(output.toString())
                .isLessThan(25.0);
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_blocking")).as(output.toString()).startsWith("true null");
        assertThat(output.value("CALL_SITES")).as(output.toString()).startsWith("true null");
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
        assertThat(output.value("BLOCKING")).as(output.toString()).contains("off=false");
    }
}
