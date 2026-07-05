package io.github.jdubois.bootui.console.activity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The console's own Server-Sent Events broadcaster for {@code GET /bootui/api/activity/stream},
 * deliberately mirroring {@code io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiChangeStream}
 * design-for-design (bounded fan-out, off-hot-path coalesced flush via a one-shot delayed {@link Mono},
 * a re-subscribable {@code autoCancel=false} sink) rather than depending on it &mdash; see {@code
 * console.web}'s package Javadoc for why the console does not depend on {@code
 * bootui-spring-autoconfigure}.
 *
 * <p>Signalled from exactly one place: {@link ConsoleActivityForwardService#receive} calls {@link
 * #signal()} after every successfully appended batch, so the dashboard refreshes the moment any
 * instance forwards new activity &mdash; the console's one and only source of change, unlike a host
 * application's several independent in-process listeners (HTTP filter, SQL interceptor, security audit
 * listener) that each call {@code signal()} on the servlet/reactive original.
 */
public final class ConsoleActivityChangeStream {

    /** Upper bound on simultaneous streams; this is a local dev tool, not a broadcast hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /** Default coalescing window: long enough to fold bursts, short enough to feel live. */
    private static final Duration DEFAULT_COALESCE = Duration.ofMillis(750L);

    /** Small bound on the sink's internal backlog; ticks are ephemeral, never business data. */
    private static final int SINK_BUFFER_SIZE = 16;

    private final Duration coalesceDuration;
    private final Sinks.Many<Long> sink = Sinks.many().multicast().onBackpressureBuffer(SINK_BUFFER_SIZE, false);
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);

    public ConsoleActivityChangeStream() {
        this(DEFAULT_COALESCE);
    }

    ConsoleActivityChangeStream(Duration coalesceDuration) {
        this.coalesceDuration = coalesceDuration;
    }

    /**
     * Opens a new SSE stream. The controller's {@code /stream} method returns this flux directly;
     * WebFlux subscribes to it once per HTTP request. No initial event is sent; the browser performs
     * its own first load and relies on this stream only for subsequent change notifications.
     */
    public Flux<ServerSentEvent<Map<String, Object>>> open() {
        return Flux.defer(() -> {
            if (subscriberCount.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                subscriberCount.decrementAndGet();
                return Flux.error(new IllegalStateException("Too many concurrent BootUI Activity Console streams"));
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
     * Signals that new activity was received. Cheap and non-blocking: a no-op when nobody is
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
}
