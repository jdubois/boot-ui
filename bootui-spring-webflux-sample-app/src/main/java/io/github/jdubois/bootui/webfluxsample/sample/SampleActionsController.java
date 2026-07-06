package io.github.jdubois.bootui.webfluxsample.sample;

import io.github.jdubois.bootui.webfluxsample.greeting.GreetingService;
import io.github.jdubois.bootui.webfluxsample.notes.NoteRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive counterpart of the servlet sample app's {@code SampleController}: a small "action lab" of
 * safe, idempotent endpoints backing the WebFlux sample app's landing page buttons, each engineered to
 * light up a specific BootUI panel. Deliberately excludes the servlet sample's Security- and Spring
 * AI-gated actions (secure admin/user, AI chat) - see {@link io.github.jdubois.bootui.webfluxsample.BootUiWebfluxSampleApplication}'s
 * Javadoc for why this app does not add Spring Security.
 *
 * <p>Every action that does non-trivial work runs on {@code Schedulers.boundedElastic()}, the same
 * off-event-loop pattern {@code NoteController}/{@code GreetingController} already use, so a slow or
 * blocking demo action never stalls Netty's event loop.
 */
@RestController
@RequestMapping("/api/sample")
public class SampleActionsController {

    private static final Logger logger = LoggerFactory.getLogger(SampleActionsController.class);

    private final NoteRepository noteRepository;
    private final GreetingService greetingService;
    private final ObservationRegistry observationRegistry;
    private final Counter ordersProcessedCounter;
    private final Timer orderDurationTimer;

    public SampleActionsController(
            NoteRepository noteRepository,
            GreetingService greetingService,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry) {
        this.noteRepository = noteRepository;
        this.greetingService = greetingService;
        this.observationRegistry = observationRegistry;
        this.ordersProcessedCounter = Counter.builder("sample.orders.processed")
                .description("Sample orders processed by the BootUI demo metrics button")
                .register(meterRegistry);
        this.orderDurationTimer = Timer.builder("sample.orders.duration")
                .description("Simulated sample order processing time")
                .register(meterRegistry);
    }

    /** Throws on purpose so the BootUI Exceptions panel has a captured failure to inspect. */
    @GetMapping("/boom")
    public Mono<String> boom() {
        return Mono.fromCallable(() -> {
                    try {
                        Integer.parseInt("not-a-number");
                        return "unreachable";
                    } catch (NumberFormatException cause) {
                        throw new IllegalStateException("Sample failure for the BootUI Exceptions panel demo", cause);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Records to a custom Micrometer {@link Counter} and {@link Timer} a bounded number of times so the
     * BootUI Metrics panel has application-specific meters to display.
     */
    @GetMapping("/metrics-burst")
    public Mono<Map<String, Object>> metricsBurst(@RequestParam(name = "count", defaultValue = "5") int count) {
        int iterations = Math.max(1, Math.min(count, 50));
        return Mono.fromCallable(() -> {
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
                    return Map.<String, Object>of(
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
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Briefly allocates and touches a bounded heap buffer (then releases it) so the BootUI Live Memory
     * and Heap Dump panels show a visible bump in heap usage.
     */
    @GetMapping("/allocate")
    public Mono<Map<String, Object>> allocate(@RequestParam(name = "mb", defaultValue = "32") int mb) {
        int megabytes = Math.max(1, Math.min(mb, 128));
        return Mono.fromCallable(() -> {
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
                    return Map.<String, Object>of(
                            "allocatedMb", megabytes,
                            "heapUsedMb", usedBytes / (1024 * 1024),
                            "checksum", checksum);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sleeps for a bounded interval on a {@code boundedElastic} worker so the BootUI Threads panel has a
     * visibly busy thread to show, without ever blocking Netty's event loop.
     */
    @GetMapping("/slow")
    public Mono<Map<String, Object>> slow(@RequestParam(name = "ms", defaultValue = "1500") long ms) {
        long millis = Math.max(0, Math.min(ms, 5000));
        return Mono.fromCallable(() -> {
                    Thread.sleep(millis);
                    return Map.<String, Object>of(
                            "sleptMillis",
                            millis,
                            "thread",
                            Thread.currentThread().getName());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Fires several short read-only queries concurrently, each briefly holding its JDBC connection, so
     * the BootUI Database Connection Pools panel shows active connections under contention.
     */
    @GetMapping("/pool-stress")
    public Mono<Map<String, Object>> poolStress(@RequestParam(name = "queries", defaultValue = "8") int queries) {
        int total = Math.max(1, Math.min(queries, 32));
        long start = System.nanoTime();
        return Flux.range(0, total)
                .flatMap(
                        i -> Mono.fromCallable(() -> noteRepository.countWithDelay(150))
                                .subscribeOn(Schedulers.boundedElastic()),
                        total)
                .collectList()
                .map(results -> {
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    return Map.<String, Object>of("queries", total, "elapsedMillis", elapsedMs);
                });
    }

    /**
     * Performs a small chain of work inside nested Micrometer observations (child spans) plus log lines,
     * so the BootUI Traces panel shows a multi-step, correlated request timeline.
     */
    @GetMapping("/chained")
    public Mono<Map<String, Object>> chained() {
        return Mono.fromCallable(() -> {
                    logger.info("Starting chained sample request");
                    List<?> notes = Observation.createNotStarted("sample.chained.notes", observationRegistry)
                            .observe(noteRepository::findAll);
                    String greeting = Observation.createNotStarted("sample.chained.greeting", observationRegistry)
                            .observe(() -> greetingService.greet("chained"));
                    int noteCount = notes == null ? 0 : notes.size();
                    logger.info("Completed chained sample request: {} notes, greeting='{}'", noteCount, greeting);
                    return Map.<String, Object>of("notes", noteCount, "greeting", greeting);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
