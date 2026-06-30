package io.github.jdubois.bootui.quarkus.web;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared construction of the panels' Server-Sent-Events change-notification streams on Quarkus, so the
 * data-driven SSE resources (Live Activity, Exceptions, SQL Trace, Security Logs) share one correct
 * lifecycle.
 *
 * <p>Returning a Mutiny {@link Multi} of {@link OutboundSseEvent} lets Quarkus REST own the stream
 * lifecycle: {@link io.smallrye.mutiny.subscription.MultiEmitter#onTermination(Runnable) onTermination}
 * fires deterministically on completion, failure, <em>or client disconnect</em> (downstream
 * cancellation), so the open-stream slot and the engine subscription are always released. A raw
 * {@code SseEventSink}, by contrast, exposes no disconnect callback in the Jakarta API and only learns
 * of a <em>quiet</em> disconnect on the next attempted send — leaking the slot and listener until the
 * next change event self-heals them. This mirrors the Spring adapter's deterministic
 * {@code SseEmitter.onCompletion/onTimeout/onError} cleanup in {@code BootUiChangeStream}.
 *
 * <p>The named {@code update} event is preserved on the wire (Quarkus REST serialises an
 * {@code OutboundSseEvent}'s {@code name} as the SSE {@code event:} field), so the shared Vue panels'
 * auto-refresh — an {@code EventSource} listening for {@code update} — behaves identically on both
 * adapters.
 */
final class SseStreams {

    private SseStreams() {}

    /** Registers a change listener and returns a handle that removes it. */
    @FunctionalInterface
    interface ChangeSource {
        Runnable subscribe(Runnable onChange);
    }

    /**
     * A bounded stream that emits a tiny {@code update} tick whenever {@code source} signals a change.
     * Once {@code maxStreams} are already open the stream completes immediately (this is a local dev
     * tool, not a fan-out hub).
     *
     * <p>Back-pressure is {@code BUFFER}, which is load-bearing for correctness, not a tuning choice: the
     * engine change-sources notify their listeners <em>outside</em> their capture lock and from arbitrary
     * (and concurrent) capture threads, so {@code emit} can be called from several threads at once.
     * Mutiny's buffering emitter is backed by a multi-producer queue with a single serialised drain, so
     * those concurrent emissions are delivered to the subscriber without violating the Reactive Streams
     * serial contract — the same safety the raw {@code SseEventSink} provided via its internal send
     * serialisation. The buffer only ever holds dev-rate {@code update} ticks (themselves bounded by the
     * engine buffers), so it does not grow without bound.
     */
    static Multi<OutboundSseEvent> updates(Sse sse, AtomicInteger openStreams, int maxStreams, ChangeSource source) {
        return Multi.createFrom()
                .<OutboundSseEvent>emitter(
                        emitter -> {
                            if (openStreams.incrementAndGet() > maxStreams) {
                                openStreams.decrementAndGet();
                                emitter.complete();
                                return;
                            }
                            Runnable unsubscribe = source.subscribe(() -> {
                                if (!emitter.isCancelled()) {
                                    emitter.emit(tick(sse));
                                }
                            });
                            emitter.onTermination(() -> {
                                unsubscribe.run();
                                openStreams.decrementAndGet();
                            });
                        },
                        BackPressureStrategy.BUFFER);
    }

    private static OutboundSseEvent tick(Sse sse) {
        return sse.newEventBuilder()
                .name("update")
                .mediaType(MediaType.TEXT_PLAIN_TYPE)
                .data("update")
                .build();
    }
}
