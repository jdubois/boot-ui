package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Capture overhead baseline for {@code docs/PLAN-v2.md} §2.2 and M0-3.
 *
 * <p>Starts the Spring MVC sample app with BootUI on and off, three times each in alternating order after one
 * discarded run, and drives the same SQL-backed route at a fixed concurrency. It reports throughput and latency
 * percentiles per run, and their medians per configuration, to {@code target/capture-overhead/spring-mvc.md}. Tracing samples every request in both configurations, so the
 * difference is BootUI's capture and not tracing.</p>
 *
 * <p>Timings depend on the machine, so this is an opt-in measurement, never a CI gate:</p>
 *
 * <pre>./mvnw -pl bootui-spring-sample-app test -Dtest=CaptureOverheadBenchmarkTest -Dbootui.benchmark=true</pre>
 *
 * <p>Milestone M2 adds a journal-on and journal-off pair to the same report.</p>
 */
@EnabledIfSystemProperty(named = "bootui.benchmark", matches = "true")
class CaptureOverheadBenchmarkTest {

    private static final String ROUTE = "/api/sample/product-search?term=console";

    private static final int CONCURRENCY = 16;

    private static final Duration WARM_UP = Duration.ofSeconds(5);

    private static final Duration MEASURE = Duration.ofSeconds(15);

    private static final int PASSES = 3;

    private static final AtomicInteger DATABASES = new AtomicInteger();

    record Result(String label, long requests, double seconds, long[] sortedNanos) {

        double throughput() {
            return requests / seconds;
        }

        double percentileMillis(double percentile) {
            if (sortedNanos.length == 0) {
                return Double.NaN;
            }
            int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
            return sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))] / 1_000_000.0;
        }
    }

    @Test
    void measuresBootUiCaptureOverhead() throws Exception {
        // The first application started in this JVM also pays for JIT-compiling shared code, so it is discarded.
        run("JVM warm-up, discarded", "ON");
        List<Result> on = new ArrayList<>();
        List<Result> off = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        for (int pass = 1; pass <= PASSES; pass++) {
            // Alternate the order (on/off, then off/on) so that drift over time does not favour one configuration.
            boolean onFirst = pass % 2 == 1;
            for (boolean bootUiOn : onFirst ? new boolean[] {true, false} : new boolean[] {false, true}) {
                Result result = run("BootUI " + (bootUiOn ? "on" : "off") + ", pass " + pass, bootUiOn ? "ON" : "OFF");
                (bootUiOn ? on : off).add(result);
                results.add(result);
            }
        }

        StringBuilder report = new StringBuilder("# Capture overhead: spring-mvc\n\n")
                .append("`GET ")
                .append(ROUTE)
                .append("` with ")
                .append(CONCURRENCY)
                .append(" concurrent clients, ")
                .append(WARM_UP.toSeconds())
                .append(" s warm-up and ")
                .append(MEASURE.toSeconds())
                .append(" s measured per run, after one discarded run. Tracing samples every request in both")
                .append(" configurations.\n\n")
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
                "%nMedian throughput with BootUI on is %.1f %% of BootUI off. Median p99 latency: %.2f ms on, %.2f ms"
                        + " off.%n",
                median(on, Result::throughput) / median(off, Result::throughput) * 100,
                median(on, result -> result.percentileMillis(99)),
                median(off, result -> result.percentileMillis(99))));

        Path directory = Path.of("target", "capture-overhead");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("spring-mvc.md"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(results).allSatisfy(result -> assertThat(result.requests()).isPositive());
    }

    private static double median(List<Result> results, ToDoubleFunction<Result> metric) {
        double[] values = results.stream().mapToDouble(metric).sorted().toArray();
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }

    private Result run(String label, String bootUi) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BootUiSampleApplication.class)
                .properties(
                        "server.port=0",
                        "spring.profiles.active=dev",
                        "spring.docker.compose.enabled=false",
                        "spring.datasource.url=jdbc:h2:mem:bootui_overhead_" + DATABASES.incrementAndGet()
                                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                        "spring.jmx.enabled=false",
                        "management.tracing.sampling.probability=1.0",
                        "logging.level.root=WARN",
                        "bootui.enabled=" + bootUi,
                        "bootui.show-banner=false",
                        "bootui.overrides-file=target/capture-overhead/application-bootui.properties")
                .run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            URI uri = URI.create("http://localhost:" + port + ROUTE);
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            load(client, uri, WARM_UP);
            long start = System.nanoTime();
            List<long[]> samples = load(client, uri, MEASURE);
            double seconds = (System.nanoTime() - start) / 1e9;
            long[] all = samples.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            return new Result(label, all.length, seconds, all);
        }
    }

    private static List<long[]> load(HttpClient client, URI uri, Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<long[]>> workers = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                workers.add(executor.submit(() -> {
                    long[] nanos = new long[1024];
                    int count = 0;
                    while (System.nanoTime() < deadline) {
                        long started = System.nanoTime();
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        long elapsed = System.nanoTime() - started;
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("Unexpected status " + response.statusCode());
                        }
                        if (count == nanos.length) {
                            nanos = Arrays.copyOf(nanos, count * 2);
                        }
                        nanos[count++] = elapsed;
                    }
                    return Arrays.copyOf(nanos, count);
                }));
            }
            List<long[]> samples = new ArrayList<>();
            for (Future<long[]> worker : workers) {
                samples.add(worker.get());
            }
            return samples;
        } finally {
            executor.shutdownNow();
        }
    }
}
