package io.github.jdubois.bootui.autoconfigure.stream;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reusable Server-Sent Events broadcaster that turns an in-process change source into coalesced
 * {@code update} notifications for the browser. It backs the panels whose data is genuinely
 * event-driven (exceptions, SQL trace, security logs), replacing fixed-interval client polling with
 * a server push the moment the underlying buffer changes.
 *
 * <p>The payload is intentionally a tiny tick ({@code {"ts": <millis>}}); the browser re-fetches the
 * existing REST endpoint on each tick, so all server-side filtering, pagination, masking, and
 * value-exposure rules continue to apply unchanged.
 *
 * <p>Design notes that keep this safe for a local dev tool:
 *
 * <ul>
 *   <li><b>Bounded fan-out.</b> No more than {@link #MAX_CONCURRENT_STREAMS} emitters are kept at
 *       once; further {@code /stream} requests are rejected. This is a developer console, not a
 *       broadcast hub.
 *   <li><b>Coalescing off the hot path.</b> {@link #signal()} is called from host-application
 *       threads (a JDBC query thread, a logging thread, the event multicaster). It only flips a
 *       dirty flag and schedules a single delayed flush, so a burst of thousands of queries produces
 *       one push, and the host thread never blocks on SSE I/O.
 *   <li><b>Self-cleaning.</b> The single daemon scheduler thread is created on first use and shut
 *       down again once the last emitter disconnects.
 * </ul>
 */
public final class BootUiChangeStream implements AutoCloseable {

    /** Upper bound on simultaneous streams for a single panel; this is a local dev tool. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /** Default coalescing window: long enough to fold bursts, short enough to feel live. */
    private static final long DEFAULT_COALESCE_MILLIS = 750L;

    private final String label;
    private final long coalesceMillis;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);

    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;

    public BootUiChangeStream(String label) {
        this(label, DEFAULT_COALESCE_MILLIS);
    }

    BootUiChangeStream(String label, long coalesceMillis) {
        this.label = label;
        this.coalesceMillis = Math.max(0L, coalesceMillis);
    }

    /**
     * Opens a new SSE stream. The caller (a {@code /stream} controller method) returns the emitter
     * directly. No initial event is sent; the browser performs its own first load and relies on this
     * stream only for subsequent change notifications.
     */
    public SseEmitter open() {
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (schedulerLock) {
            if (emitters.size() >= MAX_CONCURRENT_STREAMS) {
                emitter.completeWithError(
                        new IllegalStateException("Too many concurrent BootUI " + label + " streams"));
                return emitter;
            }
            emitters.add(emitter);
            ensureScheduler();
        }
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(error -> remove(emitter));
        return emitter;
    }

    /**
     * Signals that the underlying data changed. Cheap and non-blocking: no-op when nobody is
     * listening, otherwise schedules a single coalesced flush. Safe to call from any thread.
     */
    public void signal() {
        if (emitters.isEmpty()) {
            return;
        }
        if (flushPending.compareAndSet(false, true)) {
            synchronized (schedulerLock) {
                ScheduledExecutorService current = scheduler;
                if (current == null) {
                    // Last emitter disconnected between the empty check and here.
                    flushPending.set(false);
                    return;
                }
                try {
                    current.schedule(this::flush, coalesceMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ex) {
                    flushPending.set(false);
                }
            }
        }
    }

    private void flush() {
        flushPending.set(false);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("update")
                        .data(Map.of("ts", System.currentTimeMillis()), MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException ex) {
                remove(emitter);
                emitter.completeWithError(ex);
            }
        }
    }

    private void remove(SseEmitter emitter) {
        emitters.remove(emitter);
        synchronized (schedulerLock) {
            if (emitters.isEmpty() && scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    private void ensureScheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bootui-" + label + "-stream");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    @Override
    public void close() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    // ── testing hooks ─────────────────────────────────────────────────────────

    int subscriberCount() {
        return emitters.size();
    }

    boolean hasScheduler() {
        synchronized (schedulerLock) {
            return scheduler != null;
        }
    }
}
