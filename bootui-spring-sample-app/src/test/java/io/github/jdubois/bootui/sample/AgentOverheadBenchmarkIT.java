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
 * {@code bootui.benchmark.route=threads} drives {@value #THREADS_ROUTE}: the search plus one thread started and joined
 * and one executor created and shut down per request, for the thread-activity sensor's A/B (M5-5e).
 * {@code bootui.benchmark.report} names the report, {@code spring-mvc-agent} by default, whose summary is {@code
 * summary.properties}; any other name writes {@code <name>.md} and {@code <name>.properties}.
 *
 * <p>{@code bootui.benchmark.route=caught} drives {@value #CAUGHT_ROUTE}: the same search plus one exception caught
 * per request, half where thrown, half after unwinding through twenty application frames with handlers, then wrapped,
 * rethrown, and caught (M5-6a2), so the caught-exceptions sensor's handler-entry and exit hooks are measured.
 *
 * <p>{@code bootui.benchmark.agent.budget-percent} sets the run's budget, 10 % by default: the report prints
 * {@code PASS} or {@code FAIL} against it. {@code bootui.benchmark.agent.enforce-when-default} names a sensor: while
 * it is one of {@link AgentSensorSettings#DEFAULT_SENSORS}, a {@code FAIL} fails the run, so a sensor that ships on by
 * default can never exceed its budget unnoticed; while it is opt-in, only {@code fail-above-percent} fails it.
 *
 * <p>{@code bootui.benchmark.route=sinks} drives {@value #SINKS_ROUTE}: two query parameters, the search's SQL
 * statement, and one file read, for the security-sinks sensor's request-value matching (M5-6b). {@code
 * bootui.benchmark.agent.extra} and {@code bootui.benchmark.agent.baseline-extra} add comma-separated application
 * arguments to the agent arm and to the other arm, so an A/B can claim the same sensors with matching on and off.
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

    /**
     * The thread variant's route ({@code bootui.benchmark.route=threads}, M5-5e): the search, one thread started and
     * joined, and one executor created and shut down, so the thread-activity sensor's hooks are measured on a route
     * that runs them.
     */
    static final String THREADS_ROUTE = "/api/thread-activity/benchmark?term=console";

    /** The I/O variant's route: the search, one outbound connect, and one file read. */
    static final String IO_ROUTE = "/api/side-effects/benchmark-io?term=console";

    /** The caught-exceptions variant's route: the search and one caught exception. */
    static final String CAUGHT_ROUTE = "/api/caught/benchmark?term=console";

    /** The security-sinks variant's route (M5-6b): two query parameters, one SQL statement, and one file read. */
    static final String SINKS_ROUTE = "/api/side-effects/benchmark-sinks?term=console&tag=sample-tag";

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
        String routeName = System.getProperty("bootui.benchmark.route", "");
        boolean io = "io".equals(routeName);
        String route = io
                ? IO_ROUTE
                : "caught".equals(routeName)
                        ? CAUGHT_ROUTE
                        : "threads".equals(routeName)
                                ? THREADS_ROUTE
                                : "sinks".equals(routeName) ? SINKS_ROUTE : CaptureOverheadBenchmarkTest.ROUTE;
        List<String> agentExtra = arguments(System.getProperty("bootui.benchmark.agent.extra", ""));
        List<String> baselineExtra = arguments(System.getProperty("bootui.benchmark.agent.baseline-extra", ""));
        double budget = Double.parseDouble(
                System.getProperty("bootui.benchmark.agent.budget-percent", String.valueOf(BUDGET_PERCENT)));
        String enforced = System.getProperty("bootui.benchmark.agent.enforce-when-default", "");
        String baseline = System.getProperty("bootui.benchmark.agent.baseline-sensors", "");
        String reportName = System.getProperty("bootui.benchmark.report", "spring-mvc-agent");
        String baselineLabel = baseline.isBlank() ? "No agent" : "Agent, sensors " + baseline;
        try (Stub stub = io ? new Stub() : null) {
            List<String> extra = stub == null ? List.of() : List.of("--sample.benchmark.stub-port=" + stub.port());
            measure(
                    agent,
                    passes,
                    sensors,
                    failAbove,
                    route,
                    baseline,
                    baselineLabel,
                    reportName,
                    extra,
                    agentExtra,
                    baselineExtra,
                    budget,
                    enforced);
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
            List<String> extra,
            List<String> agentExtra,
            List<String> baselineExtra,
            double budget,
            String enforced)
            throws Exception {
        List<String> agentArguments = new ArrayList<>(extra);
        agentArguments.addAll(agentExtra);
        List<String> baselineArguments = new ArrayList<>(extra);
        baselineArguments.addAll(baselineExtra);
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
                        ? run(
                                "Agent, " + describe(sensors) + ", pass " + pass,
                                agent,
                                sensors,
                                pass,
                                route,
                                agentArguments)
                        : baseline.isBlank()
                                ? run(baselineLabel + ", pass " + pass, null, sensors, pass, route, baselineArguments)
                                : run(
                                        baselineLabel + ", pass " + pass,
                                        agent,
                                        baseline,
                                        pass,
                                        route,
                                        baselineArguments);
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
        String verdict = overheadPercent <= budget ? "PASS" : "FAIL";
        boolean enforcing = !enforced.isBlank() && AgentSensorSettings.DEFAULT_SENSORS.contains(enforced);

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
                                + " overhead, against a %.0f %% budget: %s.** Median p99 latency: %.2f ms with the"
                                + " agent, %.2f ms in the other run.%n",
                        medianRatio * 100,
                        overheadPercent,
                        budget,
                        verdict,
                        CaptureOverheadBenchmarkTest.median(with, result -> result.percentileMillis(99)),
                        CaptureOverheadBenchmarkTest.median(without, result -> result.percentileMillis(99))));

        if (!agentExtra.isEmpty() || !baselineExtra.isEmpty()) {
            report.append(String.format(
                    Locale.ROOT,
                    "%nThe agent run also passes %s; the other run %s.%n",
                    agentExtra.isEmpty() ? "nothing more" : String.join(" ", agentExtra),
                    baselineExtra.isEmpty() ? "nothing more" : "passes " + String.join(" ", baselineExtra)));
        }

        Path directory = Path.of("target", "agent-overhead");
        Files.createDirectories(directory);
        boolean defaultReport = reportName.equals("spring-mvc-agent");
        Files.writeString(directory.resolve(reportName + ".md"), report.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve(defaultReport ? "summary.properties" : reportName + ".properties"),
                String.format(
                        Locale.ROOT,
                        "overheadPercent=%.1f%nbudgetPercent=%.0f%nmedianRatio=%.4f%npasses=%d%n"
                                + "minOverheadPercent=%.1f%nmaxOverheadPercent=%.1f%nverdict=%s%nenforced=%s%n",
                        overheadPercent,
                        budget,
                        medianRatio,
                        passes,
                        (1 - Arrays.stream(ratios).max().orElse(1)) * 100,
                        (1 - Arrays.stream(ratios).min().orElse(1)) * 100,
                        verdict,
                        enforcing),
                StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.printf(
                Locale.ROOT,
                "%s: %s median overhead %.1f %% against a %.0f %% budget%s%n",
                verdict,
                reportName,
                overheadPercent,
                budget,
                enforcing ? ", enforced: " + enforced + " is on by default" : "");

        assertThat(results).allSatisfy(result -> assertThat(result.requests()).isPositive());
        if (enforcing) {
            assertThat(overheadPercent)
                    .as(
                            "%s is on by default, so its median paired overhead must stay within its %.0f %% budget:%n%s",
                            enforced, budget, report)
                    .isLessThanOrEqualTo(budget);
        }
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
            if (extra.contains(REQUEST_VALUES_ON) || extra.contains(REQUEST_VALUES_OFF)) {
                assertRequestValues(sample, extra.contains(REQUEST_VALUES_ON));
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

    static final String REQUEST_VALUES_ON = "--bootui.agent.security-sinks.request-values=true";
    static final String REQUEST_VALUES_OFF = "--bootui.agent.security-sinks.request-values=false";

    /**
     * The run measured what its arm claims (M5-6b): with matching on, the holder held requests' values and checked
     * sinks; with it off, it held none. Read from the security-sinks sensor's limitations, which carry the holder's
     * counters while matching is on.
     */
    private static void assertRequestValues(SampleExecutableJar sample, boolean on) {
        JsonNode report = sample.probe()
                .get("/bootui/api/side-effects/sensor?sensor=security-sinks")
                .json();
        String counters = null;
        for (JsonNode line : report.path("limitations")) {
            if (line.asText().startsWith("Request-value matching: ")) {
                counters = line.asText();
            }
        }
        if (!on) {
            assertThat(counters).as(report.toString()).isNull();
            return;
        }
        assertThat(counters).as(report.toString()).isNotNull();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "^Request-value matching: (\\d+) requests held values, (\\d+) sink checks ran\\.")
                .matcher(counters);
        assertThat(matcher.find()).as(counters).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).as(counters).isPositive();
        assertThat(Long.parseLong(matcher.group(2))).as(counters).isPositive();
    }

    /** Application arguments from a comma-separated property, such as {@code --a=b,--c=d}. */
    private static List<String> arguments(String property) {
        return property.isBlank()
                ? List.of()
                : Arrays.stream(property.split(","))
                        .map(String::trim)
                        .filter(text -> !text.isEmpty())
                        .toList();
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
                : Arrays.stream(sensors.split(","))
                        .map(String::trim)
                        // Its request-value matching rides on other sensors' hooks: the agent reports no hook of its
                        // own.
                        .filter(sensor -> !sensor.equals(AgentSensorSettings.SECURITY_SINKS))
                        .toList();
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
