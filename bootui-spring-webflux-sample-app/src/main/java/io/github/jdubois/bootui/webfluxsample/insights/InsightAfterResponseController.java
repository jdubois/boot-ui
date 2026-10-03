package io.github.jdubois.bootui.webfluxsample.insights;

import io.github.jdubois.bootui.webfluxsample.notes.NoteRepository;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The {@code work-after-response} seed and its counterexample on Spring WebFlux ({@code docs/PLAN-v2.md} §5.17, M5-2):
 * notes read by a raw thread pool that the response does not wait for, and the same read the response waits for. Only
 * the BootUI agent sees the first one as the request's work. Never copy these routes into an application.
 */
@RestController
@RequestMapping("/api/insights/notes/after-response")
public class InsightAfterResponseController {

    /** How long the seeded task waits before its query, so it is still running after the response. */
    static final long DELAY_MILLIS = 200;

    private final NoteRepository notes;
    private final ExecutorService pool;

    public InsightAfterResponseController(NoteRepository notes) {
        this.notes = notes;
        AtomicInteger threads = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "insight-after-response-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@code work-after-response}: answers at once while a raw pool reads the notes a little later. */
    @GetMapping
    public Mono<Map<String, Object>> readLater() {
        return Mono.fromCallable(() -> {
            CompletableFuture.runAsync(
                    () -> {
                        pause();
                        notes.findAll();
                    },
                    pool);
            return Map.<String, Object>of("status", "accepted");
        });
    }

    /** The counterexample: the same read on the same pool, which the response waits for. */
    @GetMapping("/waits")
    public Mono<Map<String, Object>> readBeforeAnswering() {
        return Mono.fromFuture(() ->
                        CompletableFuture.supplyAsync(() -> notes.findAll().size(), pool))
                .map(count -> Map.<String, Object>of("notes", count));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    private static void pause() {
        try {
            Thread.sleep(DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
