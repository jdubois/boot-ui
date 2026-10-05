package io.github.jdubois.bootui.autoconfigure.reactive;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Reactive (WebFlux) sibling of {@code BootUiChangeStream}: the same reusable Server-Sent Events
 * broadcaster contract - turn an in-process change source into coalesced {@code update}
 * notifications for the browser - built on a {@link Sinks.Many} instead of a servlet
 * {@code SseEmitter} pool. It backs the same event-driven panels (exceptions, SQL trace, security
 * logs) under WebFlux, replacing fixed-interval client polling with a server push the moment the
 * underlying buffer changes.
 *
 * <p>The payload is intentionally a tiny tick ({@code {"ts": <millis>}}); the browser re-fetches the
 * existing REST endpoint on each tick, so all server-side filtering, pagination, masking, and
 * value-exposure rules continue to apply unchanged - identical contract to the servlet original.
 *
 * <p>Design notes that keep this safe for a local dev tool (mirroring the servlet original):
 *
 * <ul>
 *   <li><b>Bounded fan-out.</b> No more than {@link #MAX_CONCURRENT_STREAMS} subscriptions are kept
 *       at once; further {@link #open()} subscriptions are rejected with an error signal. This is a
 *       developer console, not a broadcast hub.
 *   <li><b>Coalescing off the hot path.</b> {@link #signal()} is called from host-application
 *       threads (a JDBC query thread, a logging thread, an audit event listener). It only flips a
 *       dirty flag and schedules a single delayed flush on Reactor's shared parallel scheduler, so a
 *       burst of thousands of queries produces one push, and the host thread never blocks on I/O.
 *   <li><b>No dedicated thread.</b> Unlike the servlet original (which lazily starts and tears down
 *       a per-instance daemon {@code ScheduledExecutorService}), the coalesced flush is a single
 *       one-shot {@link Mono#delay(Duration)} on Reactor's shared {@code Schedulers.parallel()} -
 *       there is no per-instance thread to create or shut down.
 *   <li><b>Re-subscribable.</b> The underlying sink is created with {@code autoCancel=false}, so the
 *       stream keeps accepting new subscriptions across a subscriber count that drops to (and back
 *       up from) zero over the application's lifetime, matching the servlet original's ability to
 *       {@link #open()} a fresh emitter at any time.
 * </ul>
 */
public final class ReactiveBootUiChangeStream {

    /** Upper bound on simultaneous streams for a single panel; this is a local dev tool. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /** Default coalescing window: long enough to fold bursts, short enough to feel live. */
    private static final Duration DEFAULT_COALESCE = Duration.ofMillis(750L);

    /** Small bound on the sink's internal backlog; ticks are ephemeral, never business data. */
    private static final int SINK_BUFFER_SIZE = 16;

    private final String label;
    private final Duration coalesceDuration;
    private final Sinks.Many<Long> sink = Sinks.many().multicast().onBackpressureBuffer(SINK_BUFFER_SIZE, false);
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);

    /** Test-only counter of coalesced flushes performed. */
    private final AtomicInteger flushCount = new AtomicInteger();

    public ReactiveBootUiChangeStream(String label) {
        this(label, DEFAULT_COALESCE);
    }

    ReactiveBootUiChangeStream(String label, Duration coalesceDuration) {
        this.label = label;
        this.coalesceDuration = coalesceDuration;
    }

    /**
     * Opens a new SSE stream. The caller (a {@code /stream} controller method) returns the flux
     * directly; WebFlux subscribes to it once per HTTP request, which is the reactive analog of the
     * servlet original creating one {@code SseEmitter} per {@code open()} call. No initial event is
     * sent; the browser performs its own first load and relies on this stream only for subsequent
     * change notifications.
     */
    public Flux<ServerSentEvent<Map<String, Object>>> open() {
        return Flux.defer(() -> {
            if (subscriberCount.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                subscriberCount.decrementAndGet();
                return Flux.error(new IllegalStateException("Too many concurrent BootUI " + label + " streams"));
            }
            return sink.asFlux()
                    .map(ts -> ServerSentEvent.<Map<String, Object>>builder()
                            .event("update")
                            .data(Map.of("ts", ts))
                            .build())
                    .doFinally(signalType -> subscriberCount.decrementAndGet());
        });
    }

    /**
     * Signals that the underlying data changed. Cheap and non-blocking: no-op when nobody is
     * listening, otherwise schedules a single coalesced flush. Safe to call from any thread.
     */
    public void signal() {
        if (subscriberCount.get() <= 0) {
            return;
        }
        if (flushPending.compareAndSet(false, true)) {
            Mono.delay(coalesceDuration).subscribe(tick -> flush(), error -> flushPending.set(false));
        }
    }

    private void flush() {
        flushPending.set(false);
        flushCount.incrementAndGet();
        sink.tryEmitNext(System.currentTimeMillis());
    }

    /** Completes the stream for every current subscriber; safe to call more than once. */
    public void close() {
        sink.tryEmitComplete();
    }

    // ── testing hooks ─────────────────────────────────────────────────────────

    int subscriberCount() {
        return subscriberCount.get();
    }

    int flushCount() {
        return flushCount.get();
    }
}
