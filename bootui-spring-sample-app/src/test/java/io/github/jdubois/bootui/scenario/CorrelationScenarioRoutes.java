package io.github.jdubois.bootui.scenario;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes only the {@code docs/PLAN-v2.md} §5.1 correlation scenario calls. It lives outside the sample app's package,
 * so no other test scans it, and each coverage runner imports it.
 *
 * <p>{@code /scenario/raw-executor-query} hands its query to a plain {@link ExecutorService} the application did not
 * wrap, as many applications do, and waits for it. The pool's threads are reused across requests, so a context that
 * leaked into them, or was inherited when a request created them, would show up under the wrong request.</p>
 */
@RestController
public class CorrelationScenarioRoutes implements DisposableBean {

    /** Names of the raw executor's threads. */
    public static final String RAW_EXECUTOR_THREADS = "scenario-raw-\\d+";

    private final AtomicInteger threadNumber = new AtomicInteger();

    private final ExecutorService rawExecutor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "scenario-raw-" + threadNumber.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final JdbcTemplate jdbcTemplate;

    public CorrelationScenarioRoutes(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/scenario/raw-executor-query")
    public String rawExecutorQuery() throws Exception {
        Integer answer = rawExecutor
                .submit(() -> jdbcTemplate.queryForObject("SELECT 42", Integer.class))
                .get(10, TimeUnit.SECONDS);
        return "ok:" + answer;
    }

    @Override
    public void destroy() {
        rawExecutor.shutdownNow();
    }
}
