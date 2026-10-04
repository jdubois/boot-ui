package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The agent's cumulative overhead ({@code docs/PLAN-v2.md} §5.13 and §8, M5-12): the sample's executable jar, BootUI on
 * in both configurations, without the agent and with it attached and every default sensor claimed ({@code executors},
 * {@code inventory}, {@code code-paths}). It drives the capture overhead scenario's SQL-backed route at the same fixed
 * concurrency as {@link CaptureOverheadBenchmarkTest}, after one discarded run, in pairs whose order alternates so drift
 * favours neither configuration, and writes per-run throughput and latency, each pair's throughput ratio, and their
 * median to {@code target/agent-overhead/spring-mvc-agent.md}, with the median overhead in
 * {@code target/agent-overhead/summary.properties}. {@code bootui.benchmark.agent.sensors} claims other sensors than the
 * defaults, such as {@code executors} alone, to measure one sensor's share or a new sensor's cost.
 *
 * <p>The budget is 10 %. Timings depend on the machine, so this is opt-in. It fails only when
 * {@code bootui.benchmark.agent.fail-above-percent} is set and the median paired overhead exceeds it; CI sets it to 20,
 * twice the budget, so run-to-run noise on a shared runner never fails a build while a clear regression does:</p>
 *
 * <pre>./mvnw -pl bootui-spring-sample-app verify -Dbootui.benchmark=true -Dit.test=AgentOverheadBenchmarkIT
 *     -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@EnabledIfSystemProperty(named = "bootui.benchmark", matches = "true")
class AgentOverheadBenchmarkIT {

    /** The budget from §8: throughput with the agent's default sensors within 10 % of the same run without it. */
    static final double BUDGET_PERCENT = 10;

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

        // The load generator in this JVM pays for JIT-compiling its HTTP client in its first run, which is discarded.
        run("JVM warm-up, discarded", null, sensors, 0);
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
                        ? run("Agent, " + describe(sensors) + ", pass " + pass, agent, sensors, pass)
                        : run("No agent, pass " + pass, null, sensors, pass);
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

        StringBuilder report = new StringBuilder("# Agent overhead: spring-mvc executable jar\n\n")
                .append("`GET ")
                .append(CaptureOverheadBenchmarkTest.ROUTE)
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
                        "%nThroughput with the agent, per pair: %s %% of the same pair without it.%n",
                        String.join(
                                ", ",
                                Arrays.stream(ratios)
                                        .mapToObj(ratio -> String.format(Locale.ROOT, "%.1f", ratio * 100))
                                        .toList())))
                .append(String.format(
                        Locale.ROOT,
                        "%n**Median paired throughput with the agent is %.1f %% of without it: %.1f %% overhead,"
                                + " against a %.0f %% budget.** Median p99 latency: %.2f ms with the agent, %.2f ms"
                                + " without.%n",
                        medianRatio * 100,
                        overheadPercent,
                        BUDGET_PERCENT,
                        CaptureOverheadBenchmarkTest.median(with, result -> result.percentileMillis(99)),
                        CaptureOverheadBenchmarkTest.median(without, result -> result.percentileMillis(99))));

        Path directory = Path.of("target", "agent-overhead");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("spring-mvc-agent.md"), report.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("summary.properties"),
                String.format(
                        Locale.ROOT,
                        "overheadPercent=%.1f%nbudgetPercent=%.0f%nmedianRatio=%.4f%npasses=%d%n",
                        overheadPercent,
                        BUDGET_PERCENT,
                        medianRatio,
                        passes),
                StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(results).allSatisfy(result -> assertThat(result.requests()).isPositive());
        if (!failAbove.isBlank()) {
            assertThat(overheadPercent)
                    .as("the agent's median paired overhead, failing above %s %%:%n%s", failAbove, report)
                    .isLessThanOrEqualTo(Double.parseDouble(failAbove));
        }
    }

    private static Result run(String label, String agent, String sensors, int pass) throws Exception {
        List<String> jvmOptions = new ArrayList<>(JVM_OPTIONS);
        List<String> arguments = new ArrayList<>(ARGUMENTS);
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
            URI uri = URI.create("http://localhost:" + sample.port() + CaptureOverheadBenchmarkTest.ROUTE);
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

    private static String describe(String sensors) {
        return sensors.isBlank() ? "the default sensors (executors, inventory, code-paths)" : "sensors " + sensors;
    }

    /** The agent run measured what it claims: every sensor it asked for installed and active, code-paths recording. */
    private static void assertSensorsRecorded(JsonNode report, String sensors) {
        List<String> expected = sensors.isBlank()
                ? List.of("executors", "inventory", "code-paths")
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

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
