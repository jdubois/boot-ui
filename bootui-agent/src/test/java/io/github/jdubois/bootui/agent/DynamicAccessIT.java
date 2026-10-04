package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The experimental dynamic-access sensor in forked JVMs against the published jar (PLAN-v2 M5-9b spike): caller-sensitive
 * methods keep their caller under advice, a bounded session records reflection and proxies with their calling frame and
 * never an argument value, and it all holds beside the OpenTelemetry agent in both orders. Off without the JVM flag.
 */
class DynamicAccessIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");
    private static final String FLAG = "-D" + DynamicAccessSensor.FLAG + "=true";

    @Test
    void everyBehaviorPasses() throws Exception {
        assertBehaviors(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), FLAG), "behaviors"));
    }

    @Test
    void everyBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm =
                new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT), FLAG));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        ChildJvm.Output output = run(jvm, "behaviors");
        assertThat(output.text()).as("OpenTelemetry started").contains("opentelemetry-javaagent - version:");
        assertBehaviors(output);
    }

    @Test
    void everyBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm =
                new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY, FLAG));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        ChildJvm.Output output = run(jvm, "behaviors");
        assertThat(output.text()).as("OpenTelemetry started").contains("opentelemetry-javaagent - version:");
        assertBehaviors(output);
    }

    /** The count-only hooks: each one of §5.15's other dynamic accesses can be advised and is reached by its self-test. */
    @Test
    void everyExtendedHookIsAdvised() throws Exception {
        ChildJvm.Output output = run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), FLAG, "-D" + DynamicAccessSensor.EXTENDED_FLAG + "=true"),
                "behaviors");
        assertBehaviors(output);
        String sensor = output.value("SENSOR");
        for (String hook : List.of(
                "Class.get*Method*",
                "Class.get*Constructor*",
                "Class.get*Field*",
                "Field.get/set",
                "Class.getResource*",
                "ClassLoader.getResource*",
                "ObjectInputStream.resolveClass",
                "ObjectStreamClass.initNonProxy",
                "Proxy.getProxyClass")) {
            assertThat(sensor)
                    .as("%s in %s", hook, output)
                    .containsPattern("id=" + java.util.regex.Pattern.quote(hook)
                            + ", kind=count, type=[^,]+, transformed=true, selfTest=[1-9]");
        }
    }

    @Test
    void theSensorStaysOffWithoutTheFlag() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "disabled");
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("FAILURES")).as(output.toString()).isEqualTo("0");
    }

    /** Opt-in (-Dbootui.agent.it.benchmark=true): the per-call cost the spike report quotes; prints, never asserts. */
    @Test
    void benchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bootui.agent.it.benchmark"));
        System.out.println(run(List.of(), "bench").text());
        System.out.println(
                run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), FLAG), "bench").text());
    }

    private static ChildJvm.Output run(List<String> jvm, String mode) throws Exception {
        String jars = TestJars.jar("dynamic-app.jar", "bootuidynamicapp", List.of())
                + File.pathSeparator
                + TestJars.jar("dynamic-lib.jar", "bootuidynamiclib", List.of());
        List<String> options = new ArrayList<>(jvm);
        options.add("-Dbootui.agent.it.dynamic-jars=" + jars);
        return ChildJvm.run(options, "dynamic-access", mode);
    }

    private static void assertBehaviors(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        assertThat(output.value("FAILURES")).as(output.toString()).isEqualTo("0");
        assertThat(output.text()
                        .lines()
                        .filter(line -> line.startsWith("  PASS "))
                        .count())
                .as(output.toString())
                .isGreaterThanOrEqualTo(30);
        assertThat(output.text())
                .as("never an argument value")
                .doesNotContain("s3cr3t")
                .doesNotContain("NotThere");
        assertThat(output.value("FRAGMENT"))
                .as(output.toString())
                .contains(
                        "{\"condition\":{\"typeReached\":\"bootuidynamicapp.Isolated\"},\"type\":\"bootuidynamicapp.Hidden\","
                                + "\"methods\":[{\"name\":\"<init>\",\"parameterTypes\":[]},"
                                + "{\"name\":\"greet\",\"parameterTypes\":[\"java.lang.String\"]}]}")
                .contains("\"type\":{\"proxy\":[\"java.lang.Runnable\",\"bootuidynamicapp.Hidden$Marker\"]}")
                .contains(
                        "{\"condition\":{\"typeReached\":\"bootuidynamiclib.Reflector\"},\"type\":\"bootuidynamicapp.Hidden\"");
    }
}
