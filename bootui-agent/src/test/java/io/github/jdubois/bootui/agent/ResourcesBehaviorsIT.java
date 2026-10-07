package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The resources sensor in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5g, D46): first the JDK
 * retransformation check of its close hooks, run on every JDK the agent's suite runs on ({@code FileInputStream}, {@code
 * FileOutputStream}, {@code RandomAccessFile}, {@code FileChannelImpl}, {@code Socket}, and {@code
 * AbstractSelectableChannel}, loaded before the claim, transformed, and passing their self-tests); then its behaviors
 * with {@code thread-activity} off, alone and beside the OpenTelemetry agent in both orders: what a request left open is
 * reported, what it closed never is, a resource the collector reclaimed never closed is, a pool's sockets are a
 * library's hand-off and never reclaimed, and nothing is tracked without its close hook.
 */
class ResourcesBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    private static final List<String> REQUIRED = List.of(
            "a FileInputStream still open after its request is reported open with thread-activity off",
            "a FileInputStream in try-with-resources is never reported",
            "a FileOutputStream closed in finally is never reported",
            "a RandomAccessFile still open after its request is reported open",
            "Files.newInputStream left open is reported as its file channel",
            "Files.lines left open is reported once",
            "a FileChannel still open after its request is reported open",
            "a FileInputStream never closed and reclaimed by the collector is reported reclaimed without close()",
            "a socket closed by try-with-resources is never reported",
            "a socket still open after its request is reported open, then closed late through its stream",
            "a socket never closed and reclaimed by the collector is reported reclaimed without close()",
            "a SocketChannel still open after its request is reported open, then closed late",
            "a FileChannel closed by its thread's interruption is never reported reclaimed",
            "a library pool's socket kept past its request is reported open and closed late, the library's",
            "the JDK HttpClient's pooled connections are never reported reclaimed",
            "a resource no request or job owns is never tracked",
            "a job's stream never closed is reported reclaimed without close() under its execution",
            "BootUI's own resources are never tracked",
            "switching files off at run time keeps the resources sensor's close hooks");

    private static final List<String> HOOKS = List.of(
            "FileInputStream.close",
            "FileOutputStream.close",
            "RandomAccessFile.close",
            "FileChannelImpl.implCloseChannel",
            "Socket.close",
            "AbstractSelectableChannel.implCloseChannel",
            "FileChannelImpl.setUninterruptible");

    private static ChildJvm.Output run(List<String> jvm, String mode) throws Exception {
        return ChildJvm.run(jvm, "resources-behaviors", mode);
    }

    @Test
    void theCloseHooksRetransformTheirJdkClassesAndPassTheirSelfTests() throws Exception {
        ChildJvm.Output output = run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        String selfTest = output.value("SELF_TEST_resources");
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        for (String hook : HOOKS) {
            assertThat(selfTest)
                    .as(hook + " in " + output)
                    .containsPattern("id=" + java.util.regex.Pattern.quote(hook)
                            + ", kind=[\\w-]+, type=[^,]+, present=true, transformed=true, selfTest=passed");
        }
        assertThat(output.value("SENSOR")).as(output.toString()).contains("state=installed");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyResourcesBehaviorPasses() throws Exception {
        assertAllPass(run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "behaviors"), REQUIRED);
    }

    @Test
    void everyResourcesBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "behaviors"), REQUIRED);
    }

    @Test
    void everyResourcesBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(run(jvm, "behaviors"), REQUIRED);
    }

    /** A close hook left out fails its self-test alone: its kind is never tracked, so nothing of it looks leaked. */
    @Test
    void aMissingCloseHookLeavesItsKindUntrackedAndTheOthersRecording() throws Exception {
        ChildJvm.Output output = run(
                List.of(ChildJvm.javaAgent(ChildJvm.TEST_AGENT), "-Dbootui.agent.it.omit=FileInputStream.close"),
                "missing-close-hook");
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_resources"))
                .as(output.toString())
                .startsWith("true self-test failed for [FileInputStream.close]")
                .containsPattern("id=FileInputStream\\.close, .*?selfTest=failed");
        assertAllPass(
                output,
                List.of(
                        "without its close hook, a FileInputStream is never tracked, while a FileOutputStream still is"));
    }

    private static void assertAllPass(ChildJvm.Output output, List<String> required) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                .as(output.toString())
                .isEmpty();
        List<String> passed =
                output.text().lines().filter(line -> line.startsWith("  PASS ")).toList();
        for (String behavior : required) {
            assertThat(passed)
                    .as("%s in %s", behavior, output)
                    .anySatisfy(line -> assertThat(line).startsWith("  PASS " + behavior));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }
}
