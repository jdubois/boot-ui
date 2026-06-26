package io.github.jdubois.bootui.quarkus.sample.web;

import io.github.jdubois.bootui.quarkus.sample.catalog.CatalogService;
import io.github.jdubois.bootui.quarkus.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.quarkus.sample.catalog.SampleSettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;

/**
 * Quarkus analogue of the Spring sample's {@code SampleController}. Each endpoint is designed to
 * populate a specific BootUI panel (Metrics, Live Memory, Threads, DB Connection Pools, Traces,
 * Exceptions, etc.). The endpoints intentionally do small, bounded amounts of work.
 */
@Path("/api/sample")
@Produces(MediaType.APPLICATION_JSON)
public class SampleResource {

    private static final Logger LOG = Logger.getLogger(SampleResource.class);

    @Inject
    SampleSettings settings;

    @Inject
    CatalogService catalog;

    @Inject
    MeterRegistry meterRegistry;

    private Counter ordersProcessedCounter;
    private Timer orderDurationTimer;

    @PostConstruct
    void initMeters() {
        this.ordersProcessedCounter = Counter.builder("sample.orders.processed")
                .description("Sample orders processed by the BootUI demo metrics button")
                .register(meterRegistry);
        this.orderDurationTimer = Timer.builder("sample.orders.duration")
                .description("Simulated sample order processing time")
                .register(meterRegistry);
    }

    @GET
    @Path("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return catalog.greeting(settings.greeting(), settings.retries());
    }

    @GET
    @Path("/products")
    public List<ProductSummary> products() {
        return catalog.activeProducts();
    }

    @GET
    @Path("/product-search")
    public List<ProductSummary> productSearch(@QueryParam("term") String term) {
        return catalog.searchProducts(term == null || term.isBlank() ? "console" : term);
    }

    @GET
    @Path("/boom")
    @Produces(MediaType.TEXT_PLAIN)
    public String boom() {
        try {
            Integer.parseInt("not-a-number");
            return "unreachable";
        } catch (NumberFormatException cause) {
            throw new IllegalStateException(
                    "Sample failure for the BootUI Exceptions panel demo (apiToken=sample-secret-token)", cause);
        }
    }

    /** Records to custom Micrometer meters a bounded number of times for the Metrics panel. */
    @GET
    @Path("/metrics-burst")
    public Map<String, Object> metricsBurst(@QueryParam("count") Integer count) {
        int iterations = Math.max(1, Math.min(count == null ? 5 : count, 50));
        for (int i = 0; i < iterations; i++) {
            orderDurationTimer.record(() -> {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            ordersProcessedCounter.increment();
        }
        return Map.of(
                "iterations", iterations,
                "counter", "sample.orders.processed",
                "counterTotal", ordersProcessedCounter.count(),
                "timer", "sample.orders.duration",
                "timerCount", orderDurationTimer.count());
    }

    /** Briefly allocates and touches a bounded heap buffer for the Live Memory / Heap Dump panels. */
    @GET
    @Path("/allocate")
    public Map<String, Object> allocate(@QueryParam("mb") Integer mb) {
        int megabytes = Math.max(1, Math.min(mb == null ? 32 : mb, 128));
        byte[] buffer = new byte[megabytes * 1024 * 1024];
        for (int i = 0; i < buffer.length; i += 4096) {
            buffer[i] = (byte) i;
        }
        long checksum = 0;
        for (int i = 0; i < buffer.length; i += 1_048_576) {
            checksum += buffer[i];
        }
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        return Map.of("allocatedMb", megabytes, "heapUsedMb", usedBytes / (1024 * 1024), "checksum", checksum);
    }

    /** Sleeps for a bounded interval so the request thread is observably busy (Threads panel). */
    @GET
    @Path("/slow")
    public Map<String, Object> slow(@QueryParam("ms") Long ms) throws InterruptedException {
        long millis = Math.max(0, Math.min(ms == null ? 1500 : ms, 5000));
        Thread.sleep(millis);
        return Map.of("sleptMillis", millis, "thread", Thread.currentThread().getName());
    }

    /** Fires several short read-only queries concurrently for the DB Connection Pools panel. */
    @GET
    @Path("/pool-stress")
    public Map<String, Object> poolStress(@QueryParam("queries") Integer queries) {
        int total = Math.max(1, Math.min(queries == null ? 8 : queries, 32));
        long start = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(total, 16));
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[total];
            for (int i = 0; i < total; i++) {
                futures[i] = CompletableFuture.runAsync(() -> catalog.countWithDelay(150), executor);
            }
            CompletableFuture.allOf(futures).join();
        } finally {
            executor.shutdownNow();
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        return Map.of("queries", total, "elapsedMillis", elapsedMs);
    }

    /** Performs a small chain of work inside nested OpenTelemetry spans for the Traces panel. */
    @GET
    @Path("/chained")
    @WithSpan("sample.chained")
    public Map<String, Object> chained() {
        LOG.info("Starting chained sample request");
        List<ProductSummary> active = chainedCatalogStep();
        List<ProductSummary> matches = chainedSearchStep();
        int activeCount = active == null ? 0 : active.size();
        int matchCount = matches == null ? 0 : matches.size();
        LOG.infof("Completed chained sample request: %d active products, %d search matches", activeCount, matchCount);
        return Map.of("activeProducts", activeCount, "searchMatches", matchCount);
    }

    @WithSpan("sample.chained.catalog")
    List<ProductSummary> chainedCatalogStep() {
        return catalog.activeProducts();
    }

    @WithSpan("sample.chained.search")
    List<ProductSummary> chainedSearchStep() {
        return catalog.searchProducts("sample");
    }
}
