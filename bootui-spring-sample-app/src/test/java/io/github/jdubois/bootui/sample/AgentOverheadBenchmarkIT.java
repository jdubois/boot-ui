package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.sample.CaptureOverheadBenchmarkTest.Result;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The agent's cumulative overhead ({@code docs/PLAN-v2.md} §5.13 and §8, M5-12): the sample's executable jar, BootUI on
 * in both configurations, without the agent and with it attached and every default sensor claimed
 * ({@link AgentSensorSettings#DEFAULT_SENSORS}). It drives the capture overhead scenario's SQL-backed route at the same fixed
 * concurrency as {@link CaptureOverheadBenchmarkTest}, after one discarded run, in pairs whose order alternates so drift
 * favours neither configuration, and writes per-run throughput and latency, each pair's throughput ratio, and their
 * median to {@code target/agent-overhead/spring-mvc-agent.md}, with the median overhead in
 * {@code target/agent-overhead/summary.properties}. {@code bootui.benchmark.agent.sensors} claims other sensors than the
 * defaults, such as {@code executors} alone, to measure one sensor's share or a new sensor's cost.
 *
 * <p>{@code bootui.benchmark.route=io} drives {@value #IO_ROUTE} instead: the same search plus one outbound connect to a
 * stub server this test runs and one file read per request, so the side-effect sensors that hook connects and files
 * are measured on a route that exercises them (M5-5b). {@code bootui.benchmark.agent.baseline-sensors} runs the other
 * arm with the agent and those sensors instead of without the agent, an A/B of the sensors left out of it.
 * {@code bootui.benchmark.report} names the report, {@code spring-mvc-agent} by default, whose summary is {@code
 * summary.properties}; any other name writes {@code <name>.md} and {@code <name>.properties}.
 *
 * <p>The budget is 10 %. Timings depend on the machine, so this is opt-in. It fails only when
 * {@code bootui.benchmark.agent.fail-above-percent} is set and the median paired overhead exceeds it. CI sets it to 30,
 * well above the run-to-run noise on a shared runner, so only a clear regression fails a build (see CONTRIBUTING.md):</p>
 *
 * <pre>./mvnw -pl bootui-spring-sample-app verify -Dbootui.benchmark=true -Dit.test=AgentOverheadBenchmarkIT
 *     -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@EnabledIfSystemProperty(named = "bootui.benchmark", matches = "true")
class AgentOverheadBenchmarkIT {

    /** The budget from §8: throughput with the agent's default sensors within 10 % of the same run without it. */
    static final double BUDGET_PERCENT = 10;

    /** The I/O variant's route: the search, one outbound connect, and one file read. */
    static final String IO_ROUTE = "/api/side-effects/benchmark-io?term=console";

    private static final Duration WARM_UP = Duration.ofSeconds(10);

    private static final Duration MEASURE = Duration.ofSeconds(15);

    // The same heap and logging for both configurations; the sample logs BootUI at DEBUG by default.
    private static final List<String> JVM_OPTIONS = List.of("-Xms512m", "-Xmx512m");

    private static final List<String> ARGUMENTS = List.of(
            "--spring.jmx.enabled=false",
            "--management.tracing.sampling.probability=1.0",
            "--logging.level.root=WARN",
            "--logging.level.io.github.jdubois.bootui=WARN",
            "--logging.level.org.hibernate.SQL=WARN");

    @Test
    void measuresTheAgentsCumulativeOverheadWithItsDefaultSensors() throws Exception {
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).as("the agent jar, from the dependency plugin").isNotBlank();
        int passes = Integer.getInteger("bootui.benchmark.passes", 3);
        String sensors = System.getProperty("bootui.benchmark.agent.sensors", "");
        String failAbove = System.getProperty("bootui.benchmark.agent.fail-above-percent", "");
        boolean io = "io".equals(System.getProperty("bootui.benchmark.route", ""));
        String route = io ? IO_ROUTE : CaptureOverheadBenchmarkTest.ROUTE;
        String baseline = System.getProperty("bootui.benchmark.agent.baseline-sensors", "");
        String reportName = System.getProperty("bootui.benchmark.report", "spring-mvc-agent");
        String baselineLabel = baseline.isBlank() ? "No agent" : "Agent, sensors " + baseline;
        try (Stub stub = io ? new Stub() : null) {
            List<String> extra = stub == null ? List.of() : List.of("--sample.benchmark.stub-port=" + stub.port());
            measure(agent, passes, sensors, failAbove, route, baseline, baselineLabel, reportName, extra);
        }
    }

    private static void measure(
            String agent,
            int passes,
            String sensors,
            String failAbove,
            String route,
            String baseline,
            String baselineLabel,
            String reportName,
            List<String> extra)
            throws Exception {
        // The load generator in this JVM pays for JIT-compiling its HTTP client in its first run, which is discarded.
        run("JVM warm-up, discarded", null, sensors, 0, route, extra);
        List<Result> results = new ArrayList<>();
        List<Result> without = new ArrayList<>();
        List<Result> with = new ArrayList<>();
        double[] ratios = new double[passes];
        for (int pass = 1; pass <= passes; pass++) {
            boolean agentFirst = pass % 2 == 1;
            Result withAgent = null;
            Result withoutAgent = null;
            for (boolean attached : agentFirst ? new boolean[] {true, false} : new boolean[] {false, true}) {
                Result result = attached
                        ? run("Agent, " + describe(sensors) + ", pass " + pass, agent, sensors, pass, route, extra)
                        : baseline.isBlank()
                                ? run(baselineLabel + ", pass " + pass, null, sensors, pass, route, extra)
                                : run(baselineLabel + ", pass " + pass, agent, baseline, pass, route, extra);
                results.add(result);
                if (attached) {
                    withAgent = result;
                    with.add(result);
                } else {
                    withoutAgent = result;
                    without.add(result);
                }
            }
            ratios[pass - 1] = withAgent.throughput() / withoutAgent.throughput();
        }
        double medianRatio = median(ratios);
        double overheadPercent = (1 - medianRatio) * 100;

        StringBuilder report = new StringBuilder("# Agent overhead: spring-mvc executable jar")
                .append(reportName.equals("spring-mvc-agent") ? "" : " (" + reportName + ")")
                .append("\n\n`GET ")
                .append(route)
                .append("` with ")
                .append(CaptureOverheadBenchmarkTest.CONCURRENCY)
                .append(" concurrent clients against `java -jar` of the sample, BootUI on in both configurations; ")
                .append(WARM_UP.toSeconds())
                .append(" s warm-up and ")
                .append(MEASURE.toSeconds())
                .append(" s measured per run, ")
                .append(passes)
                .append(" pairs in alternating order after one discarded run. The agent run claims ")
                .append(describe(sensors))
                .append("; the other runs ")
                .append(baseline.isBlank() ? "without the agent" : "the agent with sensors " + baseline)
                .append(". Java ")
                .append(System.getProperty("java.version"))
                .append(", ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(" processors.\n\n")
                .append("| Run | Requests | Requests/s | p50 ms | p99 ms |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n");
        for (Result result : results) {
            report.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %.0f | %.2f | %.2f |%n",
                    result.label(),
                    result.requests(),
                    result.throughput(),
                    result.percentileMillis(50),
                    result.percentileMillis(99)));
        }
        report.append(String.format(
                        Locale.ROOT,
                        "%nThroughput with the agent, per pair: %s %% of the same pair's other run.%n",
                        String.join(
                                ", ",
                                Arrays.stream(ratios)
                                        .mapToObj(ratio -> String.format(Locale.ROOT, "%.1f", ratio * 100))
                                        .toList())))
                .append(String.format(
                        Locale.ROOT,
                        "%n**Median paired throughput with the agent is %.1f %% of the other run's: %.1f %%"
                                + " overhead, against a %.0f %% budget.** Median p99 latency: %.2f ms with the agent,"
                                + " %.2f ms in the other run.%n",
                        medianRatio * 100,
                        overheadPercent,
                        BUDGET_PERCENT,
                        CaptureOverheadBenchmarkTest.median(with, result -> result.percentileMillis(99)),
                        CaptureOverheadBenchmarkTest.median(without, result -> result.percentileMillis(99))));

        Path directory = Path.of("target", "agent-overhead");
        Files.createDirectories(directory);
        boolean defaultReport = reportName.equals("spring-mvc-agent");
        Files.writeString(directory.resolve(reportName + ".md"), report.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve(defaultReport ? "summary.properties" : reportName + ".properties"),
                String.format(
                        Locale.ROOT,
                        "overheadPercent=%.1f%nbudgetPercent=%.0f%nmedianRatio=%.4f%npasses=%d%n"
                                + "minOverheadPercent=%.1f%nmaxOverheadPercent=%.1f%n",
                        overheadPercent,
                        BUDGET_PERCENT,
                        medianRatio,
                        passes,
                        (1 - Arrays.stream(ratios).max().orElse(1)) * 100,
                        (1 - Arrays.stream(ratios).min().orElse(1)) * 100),
                StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(results).allSatisfy(result -> assertThat(result.requests()).isPositive());
        if (!failAbove.isBlank()) {
            assertThat(overheadPercent)
                    .as("the agent's median paired overhead, failing above %s %%:%n%s", failAbove, report)
                    .isLessThanOrEqualTo(Double.parseDouble(failAbove));
        }
    }

    private static Result run(String label, String agent, String sensors, int pass, String route, List<String> extra)
            throws Exception {
        List<String> jvmOptions = new ArrayList<>(JVM_OPTIONS);
        List<String> arguments = new ArrayList<>(ARGUMENTS);
        arguments.addAll(extra);
        if (agent != null) {
            jvmOptions.add("-javaagent:" + agent);
            if (!sensors.isBlank()) {
                arguments.add("--bootui.agent.sensors=" + sensors);
            }
        }
        String name = "overhead-" + (agent == null ? "off" : "on") + "-" + pass;
        try (SampleExecutableJar sample = SampleExecutableJar.start(name, jvmOptions, arguments)) {
            JsonNode report = sample.probe().get("/bootui/api/java-agent").json();
            assertThat(report.path("state").asText())
                    .as(sample.tail())
                    .isEqualTo(agent == null ? "NOT_ATTACHED" : "ARMED");
            if (agent != null) {
                awaitInventoryScan(sample);
            }
            URI uri = URI.create("http://localhost:" + sample.port() + route);
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            CaptureOverheadBenchmarkTest.load(client, uri, WARM_UP);
            long start = System.nanoTime();
            List<long[]> samples = CaptureOverheadBenchmarkTest.load(client, uri, MEASURE);
            double seconds = (System.nanoTime() - start) / 1e9;
            if (agent != null) {
                assertSensorsRecorded(
                        sample.probe().get("/bootui/api/java-agent").json(), sensors);
            }
            long[] all = samples.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            return new Result(label, all.length, seconds, all);
        }
    }

    /**
     * Code Inventory scans the application's class files off the request path once the run starts: let it end first, so
     * the measured window is charged with the sensors' steady cost, not with a start-up scan the other arm never runs.
     */
    private static void awaitInventoryScan(SampleExecutableJar sample) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            JsonNode report = sample.probe().get("/bootui/api/code-inventory").json();
            String status = report.path("scan").path("status").asText();
            if (!report.path("available").asBoolean() || !(status.equals("PENDING") || status.equals("RUNNING"))) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("The Code Inventory scan did not end in 60 s: " + sample.tail());
    }

    private static String describe(String sensors) {
        return sensors.isBlank()
                ? "the default sensors (" + String.join(", ", AgentSensorSettings.DEFAULT_SENSORS) + ")"
                : "sensors " + sensors;
    }

    /** The agent run measured what it claims: every sensor it asked for installed and active, code-paths recording. */
    private static void assertSensorsRecorded(JsonNode report, String sensors) {
        List<String> expected = sensors.isBlank()
                ? AgentSensorSettings.DEFAULT_SENSORS
                : Arrays.stream(sensors.split(",")).map(String::trim).toList();
        List<String> active = new ArrayList<>();
        for (JsonNode sensor : report.path("sensors")) {
            if ("installed".equals(sensor.path("state").asText())
                    && sensor.path("active").asBoolean()) {
                active.add(sensor.path("id").asText());
            }
            if ("code-paths".equals(sensor.path("id").asText()) && expected.contains("code-paths")) {
                assertThat(sensor.path("codePaths").path("fragmentsFlushed").asLong())
                        .as(sensor.toString())
                        .isPositive();
            }
        }
        assertThat(active).as(report.toString()).containsAll(expected);
    }

    /** A local stub server the I/O route connects to: it accepts each connection and closes it. */
    static final class Stub implements AutoCloseable {

        private final java.net.ServerSocket server;

        Stub() throws java.io.IOException {
            server = new java.net.ServerSocket(0, 1_024, java.net.InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(
                    () -> {
                        while (!server.isClosed()) {
                            try (java.net.Socket accepted = server.accept()) {
                                accepted.setSoLinger(true, 0);
                            } catch (java.io.IOException closed) {
                                // Closed with the test, or a client that went away.
                            }
                        }
                    },
                    "benchmark-stub");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws java.io.IOException {
            server.close();
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
