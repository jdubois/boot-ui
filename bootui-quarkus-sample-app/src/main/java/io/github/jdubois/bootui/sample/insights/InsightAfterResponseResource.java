package io.github.jdubois.bootui.sample.insights;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * The {@code work-after-response} seed and its counterexample on Quarkus ({@code docs/PLAN-v2.md} §5.17, M5-2): a query
 * handed to a raw thread pool that the resource does not wait for, and the same query it waits for. Only the BootUI
 * agent sees the first one as the request's work. Never copy these routes into an application.
 */
@Path("/api/insights/orders/after-response")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class InsightAfterResponseResource {

    /** How long the seeded task waits before its query, so it is still running after the response. */
    static final long DELAY_MILLIS = 200;

    @Inject
    DataSource dataSource;

    private final ExecutorService pool;

    public InsightAfterResponseResource() {
        AtomicInteger threads = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "insight-after-response-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@code work-after-response}: answers at once while a raw pool counts the orders a little later. */
    @GET
    public Map<String, Object> countLater() {
        CompletableFuture.runAsync(
                () -> {
                    pause();
                    try {
                        count();
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                pool);
        return Map.of("status", "accepted");
    }

    /** The counterexample: the same query on the same pool, which the resource waits for. */
    @GET
    @Path("/waits")
    public Map<String, Object> countBeforeAnswering() throws Exception {
        return Map.of("orders", pool.submit(this::count).get(5, TimeUnit.SECONDS));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    private int count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement count = connection.prepareStatement("select count(*) from insight_orders");
                ResultSet rows = count.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
