package io.github.jdubois.bootui.sample.insights;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code work-after-response} seed and its counterexample ({@code docs/PLAN-v2.md} §5.17, M5-2): a query handed to
 * a raw thread pool that the handler does not wait for, and the same query the handler waits for. Only the BootUI agent
 * sees the first one as the request's work: without it, the observation does not apply. Never copy these routes into
 * an application.
 */
@RestController
@RequestMapping("/api/insights/orders/after-response")
public class InsightAfterResponseController {

    /** How long the seeded task waits before its query, so it is still running after the response. */
    static final long DELAY_MILLIS = 200;

    private final JdbcTemplate jdbc;
    private final ExecutorService pool;

    public InsightAfterResponseController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        AtomicInteger threads = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "insight-after-response-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@code work-after-response}: answers at once while a raw pool counts the orders a little later. */
    @GetMapping
    public Map<String, Object> countLater() {
        CompletableFuture.runAsync(
                () -> {
                    pause();
                    jdbc.queryForObject("select count(*) from insight_orders", Integer.class);
                },
                pool);
        return Map.of("status", "accepted");
    }

    /** The counterexample: the same query on the same pool, which the handler waits for. */
    @GetMapping("/waits")
    public Map<String, Object> countBeforeAnswering() throws Exception {
        Integer orders = pool.submit(() -> jdbc.queryForObject("select count(*) from insight_orders", Integer.class))
                .get(5, TimeUnit.SECONDS);
        return Map.of("orders", orders);
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
