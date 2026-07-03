package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Periodically feeds an {@link ActivityCaptureCoordinator} from a live merged-feed {@link Supplier}, on
 * its own single-thread daemon scheduler.
 *
 * <p>Deliberately framework-neutral rather than relying on Spring's {@code @Scheduled}/
 * {@code @EnableScheduling} (which a host application may not have enabled) or a Quarkus-specific
 * scheduler: this mirrors {@link BufferedActivityStore}'s own internal {@link ScheduledExecutorService}
 * pattern, so the exact same class can be reused unchanged by a future Quarkus wiring.</p>
 *
 * <p>Not started automatically: an adapter constructs one only when persistence is enabled and calls
 * {@link #start(Duration)} once, typically right after the {@link ActivityStore} it feeds is created.
 * {@link #close()} stops the scheduler; it does not close the underlying store.</p>
 */
public final class ActivityCapturePoller implements AutoCloseable {

    private static final System.Logger log = System.getLogger(ActivityCapturePoller.class.getName());

    private final ActivityCaptureCoordinator coordinator;
    private final Supplier<List<ActivityEntryDto>> feed;
    private final ScheduledExecutorService scheduler;
    private volatile boolean started;

    public ActivityCapturePoller(ActivityCaptureCoordinator coordinator, Supplier<List<ActivityEntryDto>> feed) {
        this.coordinator = coordinator;
        this.feed = feed;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bootui-activity-capture");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts polling {@code feed} every {@code captureInterval}, ingesting whatever the coordinator has
     * not already captured on each tick. Safe to call at most once; later calls are ignored.
     */
    public synchronized void start(Duration captureInterval) {
        if (started) {
            return;
        }
        started = true;
        long intervalMillis = Math.max(1, captureInterval == null ? 2000 : captureInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::safeCapture, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Runs one capture tick synchronously (used by tests and available for a future manual trigger). */
    public void captureNow() {
        safeCapture();
    }

    private void safeCapture() {
        try {
            coordinator.ingest(feed.get());
        } catch (Throwable ex) {
            // A scheduled task that throws stops all future executions on a ScheduledExecutorService, so
            // this must never propagate; a single failed poll simply retries on the next tick.
            log.log(Level.WARNING, "Unexpected failure while polling for new Live Activity entries", ex);
        }
    }

    /**
     * Stops the scheduler after making one last synchronous capture pass, so entries produced since
     * the previous tick (up to {@code captureInterval} old) are not silently dropped on shutdown. Safe
     * to run alongside a concurrently in-flight scheduled tick: {@link ActivityCaptureCoordinator#ingest}
     * is internally synchronized, so the two calls simply serialize rather than double-capturing or
     * racing. This is a fast, in-memory-only operation (it reads already-captured signal buffers, no
     * I/O), so unlike {@link BufferedActivityStore#close()} it needs no bounded timeout of its own.
     */
    @Override
    public void close() {
        safeCapture();
        scheduler.shutdownNow();
    }
}
