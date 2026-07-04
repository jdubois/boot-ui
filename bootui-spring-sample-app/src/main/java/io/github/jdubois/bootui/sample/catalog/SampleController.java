package io.github.jdubois.bootui.sample.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/sample")
public class SampleController {

    private static final Logger logger = LoggerFactory.getLogger(SampleController.class);

    private final SampleSettings settings;
    private final SampleCatalog catalog;
    private final ObservationRegistry observationRegistry;
    private final Counter ordersProcessedCounter;
    private final Timer orderDurationTimer;
    private final RestClient quarkusRestClient;

    public SampleController(
            SampleSettings settings,
            SampleCatalog catalog,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            RestClient quarkusRestClient) {
        this.settings = settings;
        this.catalog = catalog;
        this.observationRegistry = observationRegistry;
        this.quarkusRestClient = quarkusRestClient;
        this.ordersProcessedCounter = Counter.builder("sample.orders.processed")
                .description("Sample orders processed by the BootUI demo metrics button")
                .register(meterRegistry);
        this.orderDurationTimer = Timer.builder("sample.orders.duration")
                .description("Simulated sample order processing time")
                .register(meterRegistry);
    }

    @GetMapping("/hello")
    public String hello() {
        return catalog.greeting(settings.getGreeting(), settings.getRetries());
    }

    @GetMapping("/products")
    public List<ProductSummary> products() {
        return catalog.activeProducts();
    }

    @GetMapping("/product-search")
    public List<ProductSummary> productSearch(@RequestParam(name = "term", defaultValue = "console") String term) {
        return catalog.searchProducts(term);
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object previousClickCount = session.getAttribute("sampleClickCount");
        int sampleClickCount = previousClickCount instanceof Number number ? number.intValue() + 1 : 1;
        session.setAttribute("sampleMessage", "Hello from the sample session");
        session.setAttribute("sampleCount", 42);
        session.setAttribute("sampleClickCount", sampleClickCount);
        session.setAttribute("sampleGeneratedAt", Instant.now().toString());
        session.setAttribute("apiToken", "sample-secret-token");
        return Map.of(
                "sessionId",
                session.getId(),
                "attributeCount",
                5,
                "sampleClickCount",
                sampleClickCount,
                "attributes",
                List.of("sampleMessage", "sampleCount", "sampleClickCount", "sampleGeneratedAt", "apiToken"));
    }

    @GetMapping("/boom")
    public String boom() {
        try {
            Integer.parseInt("not-a-number");
            return "unreachable";
        } catch (NumberFormatException cause) {
            throw new IllegalStateException(
                    "Sample failure for the BootUI Exceptions panel demo (apiToken=sample-secret-token)", cause);
        }
    }

    /**
     * Records to a custom Micrometer {@link Counter} and {@link Timer} a bounded number of times so the
     * BootUI Metrics panel has application-specific meters to display.
     */
    @GetMapping("/metrics-burst")
    public Map<String, Object> metricsBurst(@RequestParam(name = "count", defaultValue = "5") int count) {
        int iterations = Math.max(1, Math.min(count, 50));
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
                "iterations",
                iterations,
                "counter",
                "sample.orders.processed",
                "counterTotal",
                ordersProcessedCounter.count(),
                "timer",
                "sample.orders.duration",
                "timerCount",
                orderDurationTimer.count());
    }

    /**
     * Briefly allocates and touches a bounded heap buffer (then releases it) so the BootUI Live Memory and
     * Heap Dump panels show a visible bump in heap usage.
     */
    @GetMapping("/allocate")
    public Map<String, Object> allocate(@RequestParam(name = "mb", defaultValue = "32") int mb) {
        int megabytes = Math.max(1, Math.min(mb, 128));
        byte[] buffer = new byte[megabytes * 1024 * 1024];
        // Touch one byte per page so the pages are really committed, not just reserved.
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

    /**
     * Sleeps for a bounded interval so the request thread is observably busy, giving the BootUI Threads and
     * Metrics panels (http.server.requests timing) something to show.
     */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(name = "ms", defaultValue = "1500") long ms)
            throws InterruptedException {
        long millis = Math.max(0, Math.min(ms, 5000));
        Thread.sleep(millis);
        return Map.of("sleptMillis", millis, "thread", Thread.currentThread().getName());
    }

    /**
     * Fires several short read-only queries concurrently, each holding its JDBC connection briefly, so the
     * BootUI Database Connection Pools panel shows active connections under contention.
     */
    @GetMapping("/pool-stress")
    public Map<String, Object> poolStress(@RequestParam(name = "queries", defaultValue = "8") int queries) {
        int total = Math.max(1, Math.min(queries, 32));
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

    /**
     * Performs a small chain of work inside nested Micrometer observations (child spans) plus log lines, so the
     * BootUI Traces and Diagnostics panels show a multi-step, correlated request timeline.
     */
    @GetMapping("/chained")
    public Map<String, Object> chained() {
        logger.info("Starting chained sample request");
        List<ProductSummary> active = Observation.createNotStarted("sample.chained.catalog", observationRegistry)
                .observe(catalog::activeProducts);
        List<ProductSummary> matches = Observation.createNotStarted("sample.chained.search", observationRegistry)
                .observe(() -> catalog.searchProducts("sample"));
        int activeCount = active == null ? 0 : active.size();
        int matchCount = matches == null ? 0 : matches.size();
        logger.info("Completed chained sample request: {} active products, {} search matches", activeCount, matchCount);
        return Map.of("activeProducts", activeCount, "searchMatches", matchCount);
    }

    /**
     * Calls the companion BootUI Quarkus sample app's own secured, SQL-backed endpoint
     * ({@code GET /api/secure/products}, HTTP Basic admin/admin) over plain HTTP. W3C trace-context
     * propagation (on by default on both adapters) means this single browser click starts one trace
     * that crosses both JVMs, so the BootUI Traces panel on this app shows a merged waterfall spanning
     * this request and the Quarkus request it triggers -- including the Quarkus-side SQL query and
     * authentication event -- once the Quarkus app is configured to export to this app's OTLP receiver
     * (see the Quarkus sample's application.properties).
     */
    @GetMapping("/quarkus-secure-products")
    public Map<String, Object> quarkusSecureProducts() {
        try {
            List<ProductSummary> products = quarkusRestClient
                    .get()
                    .uri("/api/secure/products")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductSummary>>() {});
            List<ProductSummary> safeProducts = products == null ? List.of() : products;
            return Map.of(
                    "service",
                    "bootui-quarkus-sample",
                    "baseUrl",
                    settings.getQuarkusBaseUrl(),
                    "authenticatedAs",
                    "admin",
                    "productCount",
                    safeProducts.size(),
                    "products",
                    safeProducts);
        } catch (RestClientException cause) {
            throw new IllegalStateException(
                    "Could not reach the Quarkus sample app at " + settings.getQuarkusBaseUrl()
                            + ". Is it running? (./mvnw -pl bootui-quarkus-sample-app -am quarkus:dev)",
                    cause);
        }
    }
}
