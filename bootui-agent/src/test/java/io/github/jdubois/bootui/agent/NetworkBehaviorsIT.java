package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The network sensor in forked JVMs against the published jar (PLAN-v2 §5.16, M5-5b): first the JDK retransformation
 * check of its hooks alone ({@code Socket.connect}, {@code SocketChannelImpl.connect}, {@code blockingConnect}, and
 * {@code finishConnect}, {@code DatagramChannelImpl.send}, {@code DatagramSocket.send}, and {@code
 * InetAddress.getAddressesFromNameService}: present, transformed, and passing their self-test), run on every JDK the
 * agent's suite runs on; then its behaviors, beside the OpenTelemetry agent in both orders, beside JFR's socket events
 * in both orders, and with Mockito's inline mock maker mocking {@code Socket} in both orders; and its cost per
 * operation, which it prints for the report.
 */
class NetworkBehaviorsIT {

    private static final String OPENTELEMETRY = System.getProperty("opentelemetry.agent.jar");
    private static final List<String> OPENTELEMETRY_OPTIONS =
            List.of("-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none");

    static final List<String> HOOKS = List.of(
            "Socket.connect",
            "SocketChannel.connect",
            "SocketChannel.blockingConnect",
            "SocketChannel.finishConnect",
            "DatagramChannel.send",
            "DatagramSocket.send",
            "InetAddress.lookup");

    private static final List<String> REQUIRED = List.of(
            "a blocking Socket connect records its host and port, its time, and its call site, never a byte",
            "a refused connect records its failure",
            "a non-blocking connect records pending, then its finish with its time and owner",
            "SocketChannel.socket().connect records through blockingConnect",
            "a JDK HttpClient connect names its client, never the request's URI",
            "an HttpURLConnection connect names its client",
            "datagram sends record their target once with frames and count the rest, never a byte",
            "a lookup the JVM's cache missed records its resolution time, a cached one and a literal record nothing",
            "unowned work names its thread family",
            "BootUI's own work is never recorded",
            "a bootui- thread's connect is never recorded",
            "user information never reaches a target",
            "release restores the network classes");

    @Test
    void everyNetworkHookRetransformsItsJdkClassAndPassesItsSelfTest() throws Exception {
        ChildJvm.Output output =
                ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "network-behaviors", "check");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        String selfTest = output.value("SELF_TEST_network");
        assertThat(selfTest).as(output.toString()).startsWith("true null");
        for (String hook : HOOKS) {
            assertThat(selfTest)
                    .as("%s on JDK %s: %s", hook, Runtime.version(), output)
                    .contains("id=" + hook + ",")
                    .containsPattern("id=" + java.util.regex.Pattern.quote(hook)
                            + ", kind=record, type=[^,]+, present=true, transformed=true, selfTest=passed");
        }
        assertThat(output.value("SENSOR"))
                .as(output.toString())
                .contains("state=installed")
                .contains("hooksLeftOut=[]")
                .contains("failed=0");
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    @Test
    void everyNetworkBehaviorPasses() throws Exception {
        assertAllPass(ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "network-behaviors", "behaviors"));
    }

    @Test
    void everyNetworkBehaviorPassesAfterOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-javaagent:" + OPENTELEMETRY, ChildJvm.javaAgent(ChildJvm.AGENT)));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "network-behaviors", "behaviors"));
    }

    @Test
    void everyNetworkBehaviorPassesBeforeOpenTelemetry() throws Exception {
        List<String> jvm = new ArrayList<>(List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + OPENTELEMETRY));
        jvm.addAll(OPENTELEMETRY_OPTIONS);
        assertAllPass(ChildJvm.run(jvm, "network-behaviors", "behaviors"));
    }

    /** JFR's socket events instrument the JDK's socket classes too, before or after the claim: both keep recording. */
    @Test
    void jfrSocketEventsAndTheSensorBothRecordInEitherOrder() throws Exception {
        for (String order : List.of("jfr-first", "jfr-after")) {
            ChildJvm.Output output =
                    ChildJvm.run(List.of(ChildJvm.javaAgent(ChildJvm.AGENT)), "network-behaviors", order);

            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("SELF_TEST_network")).as(output.toString()).startsWith("true null");
            assertThat(output.value("JFR")).as(output.toString()).startsWith("ok");
            assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                    .as(output.toString())
                    .isEmpty();
            assertThat(output.text())
                    .as(output.toString())
                    .contains("  PASS a blocking Socket connect records")
                    .contains("  PASS a non-blocking connect records pending")
                    .contains("  PASS SocketChannel.socket().connect records");
            assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        }
    }

    /** Mockito's inline mock maker retransforms {@code Socket} to mock it, before or after the claim. */
    @Test
    void aMockedSocketAndARealOneCoexistInBothTransformerOrders() throws Exception {
        for (String order : List.of("bootui-first", "mockito-first")) {
            String classPath = String.join(
                    File.pathSeparator,
                    System.getProperty("mockito.jar"),
                    System.getProperty("bytebuddy.jar"),
                    System.getProperty("bytebuddy.agent.jar"),
                    System.getProperty("objenesis.jar"));
            ChildJvm.Output output = ChildJvm.runWithClassPath(
                    List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-javaagent:" + System.getProperty("mockito.jar")),
                    classPath,
                    "network-behaviors",
                    order);

            assertThat(output.exitCode()).as(output.toString()).isZero();
            assertThat(output.value("SELF_TEST_network")).as(output.toString()).startsWith("true null");
            assertThat(output.value("MOCKITO")).as(output.toString()).startsWith("ok");
            assertThat(output.text().lines().filter(line -> line.startsWith("  FAIL")))
                    .as(output.toString())
                    .isEmpty();
            assertThat(output.text())
                    .as(output.toString())
                    .contains("  PASS a real Socket beside a Mockito mock still records");
            assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
        }
    }

    /**
     * Each operation's cost with the sensor recording and switched off, printed for the report: a loopback connect, a
     * datagram send, and a lookup the JVM's cache misses. Bounds are loose, as a shared CI runner is noisy: the
     * figures, not the bounds, are the measure.
     */
    @Test
    void theSensorsCostPerOperationStaysWithinItsBudget() throws Exception {
        ChildJvm.Output output = ChildJvm.run(
                List.of(ChildJvm.javaAgent(ChildJvm.AGENT), "-Dsun.net.inetaddr.ttl=0"),
                "network-behaviors",
                "overhead");

        assertThat(output.exitCode()).as(output.toString()).isZero();
        for (String operation : List.of("connect", "datagram", "lookup")) {
            String line = output.value("OVERHEAD_" + operation);
            System.out.println("Network sensor overhead, " + operation + " on JDK " + Runtime.version() + ": " + line);
            assertThat(line).as(output.toString()).isNotNull();
            long off = Long.parseLong(line.replaceAll(".*off=(-?\\d+).*", "$1"));
            long delta = Long.parseLong(line.replaceAll(".*delta=(-?\\d+).*", "$1"));
            assertThat(delta)
                    .as(
                            "%s never costs twice as much, nor 250 µs more, with the sensor recording: %s",
                            operation, output)
                    .isLessThan(Math.max(250_000L, off));
        }
        assertThat(output.value("STATUS")).as(output.toString()).contains("errors=0");
    }

    private static void assertAllPass(ChildJvm.Output output) {
        assertThat(output.exitCode()).as(output.toString()).isZero();
        assertThat(output.value("SELF_TEST_network")).as(output.toString()).startsWith("true null");
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
        assertThat(output.value("NETWORK")).as(output.toString()).contains("off=false");
        assertThat(output.text())
                .as("no payload ever printed by the bridge")
                .doesNotContain("BOOTUI-NETWORK-PAYLOAD-never-recorded");
    }
}
