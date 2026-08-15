package io.github.jdubois.bootui.quarkus.web;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * <p>Changes are serialized and coalesced: while downstream has no demand, any number of source
     * notifications becomes one pending {@code update}. This preserves the notification semantics (the
     * browser re-fetches the complete bounded snapshot) without an unbounded per-client queue when a tab or
     * connection is slow.
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
                            CoalescingTickEmitter ticks = new CoalescingTickEmitter(emitter, sse);
                            emitter.onRequest(ignored -> ticks.drain());
                            Runnable unsubscribe = source.subscribe(ticks::signal);
                            emitter.onTermination(() -> {
                                ticks.terminate();
                                unsubscribe.run();
                                openStreams.decrementAndGet();
                            });
                        },
                        BackPressureStrategy.DROP);
    }

    private static final class CoalescingTickEmitter {

        private final io.smallrye.mutiny.subscription.MultiEmitter<? super OutboundSseEvent> emitter;
        private final Sse sse;
        private final AtomicBoolean pending = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicInteger drainWork = new AtomicInteger();

        private CoalescingTickEmitter(
                io.smallrye.mutiny.subscription.MultiEmitter<? super OutboundSseEvent> emitter, Sse sse) {
            this.emitter = emitter;
            this.sse = sse;
        }

        private void signal() {
            if (!terminated.get()) {
                pending.set(true);
                drain();
            }
        }

        private void drain() {
            if (drainWork.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            do {
                if (!terminated.get()
                        && !emitter.isCancelled()
                        && emitter.requested() > 0
                        && pending.compareAndSet(true, false)) {
                    emitter.emit(tick(sse));
                }
                missed = drainWork.addAndGet(-missed);
            } while (missed != 0);
        }

        private void terminate() {
            terminated.set(true);
            pending.set(false);
        }
    }

    private static OutboundSseEvent tick(Sse sse) {
        return sse.newEventBuilder()
                .name("update")
                .mediaType(MediaType.TEXT_PLAIN_TYPE)
                .data("update")
                .build();
    }
}
