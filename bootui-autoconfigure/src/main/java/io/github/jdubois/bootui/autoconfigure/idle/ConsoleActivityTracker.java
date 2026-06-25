package io.github.jdubois.bootui.autoconfigure.idle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks whether the local BootUI console is being used and reclaims live diagnostic buffers when it
 * is not.
 *
 * <p>Every request that reaches a BootUI route marks the console active (see
 * {@code ConsoleActivityFilter}). A single daemon thread periodically checks how long it has been
 * since the last such request: once that exceeds {@code idleTimeout} the tracker flips to idle and
 * calls {@link IdleReclaimable#suspendForIdle()} on every registered buffer, releasing the memory
 * they hold. The next console request flips the state back and calls
 * {@link IdleReclaimable#resumeFromIdle()} so the buffers refill from live traffic.</p>
 *
 * <p>The design mirrors BootUI's other background helpers: a single lightweight daemon thread, all
 * work off the request hot path ({@link #markActive()} only writes a timestamp and, on the rare
 * active/idle transition, fans out to the buffers), and fail-soft callbacks so a misbehaving buffer
 * can never break request handling or the scheduler.</p>
 */
public final class ConsoleActivityTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsoleActivityTracker.class);

    /** Never poll more often than this, even for very short idle timeouts. */
    private static final long MIN_CHECK_INTERVAL_MILLIS = 1_000L;

    /** Always notice idleness within at most this long after the timeout elapses. */
    private static final long MAX_CHECK_INTERVAL_MILLIS = 30_000L;

    private final long idleTimeoutMillis;
    private final List<IdleReclaimable> buffers;
    private final LongSupplier clock;
    private final AtomicBoolean idle = new AtomicBoolean(false);

    private volatile long lastActiveAtMillis;
    private final ScheduledExecutorService scheduler;

    public ConsoleActivityTracker(Duration idleTimeout, List<IdleReclaimable> buffers) {
        this(idleTimeout, buffers, true, System::currentTimeMillis);
    }

    ConsoleActivityTracker(
            Duration idleTimeout, List<IdleReclaimable> buffers, boolean autoSchedule, LongSupplier clock) {
        this.idleTimeoutMillis = Math.max(MIN_CHECK_INTERVAL_MILLIS, idleTimeout.toMillis());
        this.buffers = List.copyOf(buffers);
        this.clock = clock;
        this.lastActiveAtMillis = clock.getAsLong();
        if (autoSchedule) {
            long checkInterval =
                    Math.max(MIN_CHECK_INTERVAL_MILLIS, Math.min(idleTimeoutMillis, MAX_CHECK_INTERVAL_MILLIS));
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bootui-idle-reclaim");
                thread.setDaemon(true);
                return thread;
            });
            this.scheduler.scheduleWithFixedDelay(
                    this::reclaimIfIdle, checkInterval, checkInterval, TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
        }
    }

    /**
     * Records console activity. Cheap and non-blocking on the request path: it stamps the last-active
     * time and, only on an idle&rarr;active transition, resumes the registered buffers.
     */
    public void markActive() {
        lastActiveAtMillis = clock.getAsLong();
        if (idle.compareAndSet(true, false)) {
            for (IdleReclaimable buffer : buffers) {
                try {
                    buffer.resumeFromIdle();
                } catch (RuntimeException ex) {
                    log.debug("BootUI idle-reclaim: buffer {} failed to resume", buffer, ex);
                }
            }
            log.debug("BootUI console active again; resumed live diagnostic recording.");
        }
    }

    /**
     * Suspends the registered buffers when the console has been idle longer than the timeout. Invoked
     * by the scheduler; package-visible so tests can drive it deterministically.
     */
    void reclaimIfIdle() {
        if (idle.get()) {
            return;
        }
        if (clock.getAsLong() - lastActiveAtMillis < idleTimeoutMillis) {
            return;
        }
        if (idle.compareAndSet(false, true)) {
            for (IdleReclaimable buffer : buffers) {
                try {
                    buffer.suspendForIdle();
                } catch (RuntimeException ex) {
                    log.debug("BootUI idle-reclaim: buffer {} failed to suspend", buffer, ex);
                }
            }
            log.debug(
                    "BootUI console idle for over {} ms; released {} live diagnostic buffer(s) to reclaim memory.",
                    idleTimeoutMillis,
                    buffers.size());
        }
    }

    boolean isIdle() {
        return idle.get();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
