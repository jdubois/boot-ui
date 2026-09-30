package io.github.jdubois.bootui.quarkus.it;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Hands its query to a plain {@link ExecutorService} the application did not wrap, and waits for it, for the
 * {@code docs/PLAN-v2.md} §5.1 correlation scenario. The pool's threads are reused across requests, so a context that
 * leaked into them would show up under the wrong request. BootUI does not propagate into such an executor, so the
 * query must stay unowned.
 */
@Path("/it/raw-executor-sql")
@ApplicationScoped
public class RawExecutorProbeResource {

    /** Names of the raw executor's threads. */
    static final String RAW_EXECUTOR_THREADS = "it-raw-\\d+";

    private final AtomicInteger threadNumber = new AtomicInteger();

    private final ExecutorService rawExecutor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "it-raw-" + threadNumber.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    DataSource dataSource;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String runQuery() throws Exception {
        return rawExecutor.submit(this::query).get(10, TimeUnit.SECONDS);
    }

    private String query() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 42")) {
            resultSet.next();
            return "ok:" + resultSet.getInt(1);
        }
    }

    @PreDestroy
    void shutdown() {
        rawExecutor.shutdownNow();
    }
}
