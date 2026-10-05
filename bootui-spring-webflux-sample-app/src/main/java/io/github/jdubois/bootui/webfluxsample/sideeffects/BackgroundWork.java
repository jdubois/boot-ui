package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Thread activity's seeds ({@code docs/PLAN-v2.md} §5.16, M5-5e): with the BootUI agent's {@code thread-activity}
 * sensor, {@link #startRefresher()} shows in Side Effects as a thread its request left running, and {@link
 * #exportWithOwnPool()} as an executor created per request and never shut down; their counterexamples, a thread joined
 * before the response ({@link #refreshNow()}) and an executor shut down in {@code finally} ({@link
 * #exportWithClosedPool()}), are never reported left running.
 */
@Component
public class BackgroundWork {

    private static final AtomicInteger REFRESHERS = new AtomicInteger();

    /** How long a thread a request leaves behind keeps running: long enough to outlive the request, then it ends. */
    static final long LEFT_RUNNING_MILLIS = 3_000L;

    /**
     * Starts a {@code report-refresher-{n}} thread and returns without waiting for it: it is still running when the
     * request ends.
     */
    public String startRefresher() {
        Thread thread = new Thread(BackgroundWork::pause, "report-refresher-" + REFRESHERS.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        return thread.getName();
    }

    /** The counterexample: starts a thread and joins it before the request ends. */
    public String refreshNow() throws InterruptedException {
        Thread thread = new Thread(() -> {}, "report-refresh-now-" + REFRESHERS.incrementAndGet());
        thread.start();
        thread.join();
        return thread.getName();
    }

    /**
     * Creates an executor for this request and never shuts it down: still running when the request ends. Its one
     * thread times out after a few seconds, so repeated calls never accumulate threads, and the collector reclaims the
     * executor, never shut down.
     */
    public int exportWithOwnPool() throws Exception {
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(1, 1, 2, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        return pool.submit(() -> 42).get();
    }

    /** The counterexample: an executor created for this request and shut down in {@code finally}. */
    public int exportWithClosedPool() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> 42).get();
        } finally {
            pool.shutdown();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(LEFT_RUNNING_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
