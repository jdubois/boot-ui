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
 * The runtime journal's throughput overhead ({@code docs/PLAN-v2.md} §2.2 and §8, the "before 2.0.0, overhead" gate in
 * {@code docs/V2-VALIDATION-REPORT.md}): the sample's executable jar, BootUI on in both configurations and no agent, with
 * the journal on (its default) and off ({@code bootui.runtime-journal.enabled=false}). It drives the capture overhead
 * scenario's SQL-backed route at the same fixed concurrency as {@link CaptureOverheadBenchmarkTest}, after one discarded
 * run, in pairs whose order alternates so drift favours neither configuration, exactly as {@link AgentOverheadBenchmarkIT}
 * does. It writes per-run throughput and latency, each pair's throughput ratio, their median, and the median's 95 %
 * distribution-free interval to {@code target/journal-overhead/spring-mvc-journal.md}, with the figures in
 * {@code target/journal-overhead/spring-mvc-journal.properties}. Each run checks that its arm measured what it claims:
 * the journal on and recording, or off.
 *
 * <p>The target is 5 %. It only reports, never fails on the figure: timings depend on the machine, and the release
 * sign-off reads the figure from CI. {@code bootui.benchmark.passes} sets the number of pairs, 3 by default:</p>
 *
 * <pre>./mvnw -pl bootui-spring-sample-app verify -Dbootui.benchmark=true -Dit.test=JournalOverheadBenchmarkIT
 *     -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@EnabledIfSystemProperty(named = "bootui.benchmark", matches = "true")
class JournalOverheadBenchmarkIT {

    /** The target from §2.2 and §8: throughput with the journal on within 5 % of the same run with it off. */
    static final double TARGET_PERCENT = 5;

    static final String REPORT = "spring-mvc-journal";

    private static final Duration WARM_UP = Duration.ofSeconds(10);

    private static final Duration MEASURE = Duration.ofSeconds(15);

    // The same heap, logging, and tracing as the agent overhead benchmark, in both configurations.
    private static final List<String> JVM_OPTIONS = List.of("-Xms512m", "-Xmx512m");

    private static final List<String> ARGUMENTS = List.of(
            "--spring.jmx.enabled=false",
            "--management.tracing.sampling.probability=1.0",
            "--logging.level.root=WARN",
            "--logging.level.io.github.jdubois.bootui=WARN",
            "--logging.level.org.hibernate.SQL=WARN");

    @Test
    void measuresTheRuntimeJournalsThroughputOverhead() throws Exception {
        int passes = Integer.getInteger("bootui.benchmark.passes", 3);
        String route = CaptureOverheadBenchmarkTest.ROUTE;
        // The load generator in this JVM pays for JIT-compiling its HTTP client in its first run, which is discarded.
        run("JVM warm-up, discarded", true, 0, route);
        List<Result> results = new ArrayList<>();
        List<Result> on = new ArrayList<>();
        List<Result> off = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        double[] ratios = new double[passes];
        for (int pass = 1; pass <= passes; pass++) {
            boolean onFirst = pass % 2 == 1;
            Result journalOn = null;
            Result journalOff = null;
            for (boolean journal : onFirst ? new boolean[] {true, false} : new boolean[] {false, true}) {
                Run run = run("Journal " + (journal ? "on" : "off") + ", pass " + pass, journal, pass, route);
                results.add(run.result());
                if (journal) {
                    journalOn = run.result();
                    on.add(run.result());
                    dropped.add(run.droppedEvents());
                } else {
                    journalOff = run.result();
                    off.add(run.result());
                }
            }
            ratios[pass - 1] = journalOn.throughput() / journalOff.throughput();
        }
        double medianRatio = median(ratios);
        double overheadPercent = (1 - medianRatio) * 100;
        double[] interval = AgentOverheadBenchmarkIT.medianInterval(ratios);
        // Ratios run opposite to overhead: the interval's high ratio is its low overhead.
        double lowOverheadPercent = (1 - interval[1]) * 100;
        double highOverheadPercent = (1 - interval[0]) * 100;
        double p99On = CaptureOverheadBenchmarkTest.median(on, result -> result.percentileMillis(99));
        double p99Off = CaptureOverheadBenchmarkTest.median(off, result -> result.percentileMillis(99));
        String verdict = overheadPercent <= TARGET_PERCENT ? "PASS" : "FAIL";
        long droppedEvents = dropped.stream().mapToLong(Long::longValue).sum();

        StringBuilder report = new StringBuilder("# Runtime journal overhead: spring-mvc executable jar\n\n`GET ")
                .append(route)
                .append("` with ")
                .append(CaptureOverheadBenchmarkTest.CONCURRENCY)
                .append(" concurrent clients against `java -jar` of the sample, BootUI on and no agent in both")
                .append(
                        " configurations, the journal on (its default) and off (`bootui.runtime-journal.enabled=false`); ")
                .append(WARM_UP.toSeconds())
                .append(" s warm-up and ")
                .append(MEASURE.toSeconds())
                .append(" s measured per run, ")
                .append(passes)
                .append(" pairs in alternating order after one discarded run. Tracing samples every request in both")
                .append(" configurations. Java ")
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
                        "%nThroughput with the journal on, per pair: %s %% of the same pair's run with it off.%n",
                        String.join(
                                ", ",
                                Arrays.stream(ratios)
                                        .mapToObj(ratio -> String.format(Locale.ROOT, "%.1f", ratio * 100))
                                        .toList())))
                .append(String.format(
                        Locale.ROOT,
                        "%n**Median paired throughput with the journal on is %.1f %% of the run with it off: %.1f %%"
                                + " overhead, against a %.0f %% target: %s.** The median overhead is between %.1f %% and"
                                + " %.1f %% with %.0f %% confidence (distribution-free, from the pairs' order statistics)."
                                + " Median p99 latency: %.2f ms with the journal on, %.2f ms with it off. The journal"
                                + " dropped %d events across the paired runs with it on, each counted from the sample's"
                                + " start.%n",
                        medianRatio * 100,
                        overheadPercent,
                        TARGET_PERCENT,
                        verdict,
                        lowOverheadPercent,
                        highOverheadPercent,
                        interval[2] * 100,
                        p99On,
                        p99Off,
                        droppedEvents));

        Path directory = Path.of("target", "journal-overhead");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(REPORT + ".md"), report.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve(REPORT + ".properties"),
                String.format(
                        Locale.ROOT,
                        "overheadPercent=%.1f%ntargetPercent=%.0f%nmedianRatio=%.4f%npasses=%d%n"
                                + "minOverheadPercent=%.1f%nmaxOverheadPercent=%.1f%nverdict=%s%n"
                                + "lowOverheadPercent=%.1f%nhighOverheadPercent=%.1f%nintervalConfidence=%.3f%n"
                                + "p99OnMillis=%.2f%np99OffMillis=%.2f%ndroppedEvents=%d%n",
                        overheadPercent,
                        TARGET_PERCENT,
                        medianRatio,
                        passes,
                        (1 - Arrays.stream(ratios).max().orElse(1)) * 100,
                        (1 - Arrays.stream(ratios).min().orElse(1)) * 100,
                        verdict,
                        lowOverheadPercent,
                        highOverheadPercent,
                        interval[2],
                        p99On,
                        p99Off,
                        droppedEvents),
                StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.printf(
                Locale.ROOT,
                "%s: %s median overhead %.1f %% (95 %% interval %.1f to %.1f %%) against a %.0f %% target%n",
                verdict,
                REPORT,
                overheadPercent,
                lowOverheadPercent,
                highOverheadPercent,
                TARGET_PERCENT);

        assertThat(results).allSatisfy(result -> assertThat(result.requests()).isPositive());
    }

    private record Run(Result result, long droppedEvents) {}

    private static Run run(String label, boolean journal, int pass, String route) throws Exception {
        List<String> arguments = new ArrayList<>(ARGUMENTS);
        arguments.add("--bootui.runtime-journal.enabled=" + journal);
        String name = "journal-overhead-" + (journal ? "on" : "off") + "-" + pass;
        try (SampleExecutableJar sample = SampleExecutableJar.start(name, JVM_OPTIONS, arguments)) {
            assertThat(sample.probe()
                            .get("/bootui/api/java-agent")
                            .json()
                            .path("state")
                            .asText())
                    .as(sample.tail())
                    .isEqualTo("NOT_ATTACHED");
            URI uri = URI.create("http://localhost:" + sample.port() + route);
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            CaptureOverheadBenchmarkTest.load(client, uri, WARM_UP);
            long start = System.nanoTime();
            List<long[]> samples = CaptureOverheadBenchmarkTest.load(client, uri, MEASURE);
            double seconds = (System.nanoTime() - start) / 1e9;
            JsonNode status = sample.probe().get("/bootui/api/activity/journal").json();
            assertThat(status.path("enabled").asBoolean()).as(status.toString()).isEqualTo(journal);
            long recorded = 0;
            for (JsonNode count : status.path("recorded")) {
                recorded += count.asLong();
            }
            if (journal) {
                assertThat(recorded)
                        .as("the journal recorded the load: %s", status)
                        .isPositive();
            } else {
                assertThat(recorded)
                        .as("the journal recorded nothing: %s", status)
                        .isZero();
            }
            long[] all = samples.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            return new Run(
                    new Result(label, all.length, seconds, all),
                    status.path("droppedEvents").asLong());
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
