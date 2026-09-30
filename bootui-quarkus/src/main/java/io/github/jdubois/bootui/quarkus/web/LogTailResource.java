package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.logtail.LogTailReader;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JAX-RS resource for the Log Tail panel ({@code GET /bootui/api/log-tail/recent} and the SSE stream
 * {@code GET /bootui/api/log-tail/stream}). The Quarkus analogue of the Spring adapter's
 * {@code LogTailController}: a thin transport over the shared engine {@link LogTailBuffer}, fed on this
 * platform by {@code QuarkusLogTailHandler}. Both adapters serve the identical wire (an {@code "log"}
 * event carrying a {@code LogLineDto}) so the shared Vue panel renders the same. Snapshots and streamed
 * lines are read through the engine {@link LogTailReader}, which applies the live value-exposure policy
 * to each message as it is emitted.
 */
@Path("/bootui/api/log-tail")
public class LogTailResource {

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final LogTailReader reader;
    private final Supplier<Executor> deliveryExecutor;
    private final ExecutorService ownedExecutor;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public LogTailResource(LogTailBuffer buffer, QuarkusExposurePolicy exposure) {
        this(buffer, exposure, deliveryExecutor());
    }

    private LogTailResource(LogTailBuffer buffer, ExposurePolicy exposure, ExecutorService deliveryExecutor) {
        this.reader = new LogTailReader(buffer, exposure);
        this.deliveryExecutor = () -> deliveryExecutor;
        this.ownedExecutor = deliveryExecutor;
    }

    LogTailResource(LogTailBuffer buffer, ExposurePolicy exposure, Supplier<Executor> deliveryExecutor) {
        this.reader = new LogTailReader(buffer, exposure);
        this.deliveryExecutor = deliveryExecutor;
        this.ownedExecutor = null;
    }

    /**
     * Dedicated delivery threads, one per concurrently draining stream, so that a line logged while a stream line is
     * exposed or written is never captured and streamed back.
     */
    private static ExecutorService deliveryExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_CONCURRENT_STREAMS,
                MAX_CONCURRENT_STREAMS,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                LogTailBuffer.deliveryThreadFactory("bootui-log-tail-stream-"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    @GET
    @Path("/recent")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LogLineDto> recent() {
        return reader.recent();
    }

    /**
     * Streams the backlog, then live lines. The logging thread only queues each captured line; {@code emitOn} delivers
     * it on a dedicated delivery thread, where it is exposed under the policy in force at that moment, so a policy change also applies
     * to lines still queued for a slow client. The queue is bounded like the servlet stream's: a client that falls
     * {@link LogTailReader#MAX_PENDING_LINES} lines behind is disconnected rather than buffering without bound.
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        StreamRegistration registration = new StreamRegistration();
        AtomicInteger undelivered = new AtomicInteger();
        return Multi.createFrom()
                .<LogLineDto>emitter(
                        emitter -> {
                            if (!registration.acquire()) {
                                emitter.complete();
                                return;
                            }
                            // Atomically snapshot the backlog and register the live subscriber under one
                            // lock, then replay the backlog: no line is dropped or duplicated across the
                            // replay/live boundary. Live delivery arrives on arbitrary logging threads; the
                            // emitter's buffer is a multi-producer queue with a single serialised drain.
                            LogTailBuffer.Subscription subscription =
                                    reader.subscribeWithReplay(line -> offer(emitter, line, undelivered, registration));
                            emitter.onTermination(registration::release);
                            registration.attach(subscription.unsubscribe());
                            for (LogLineDto line : subscription.backlog()) {
                                if (!offer(emitter, line, undelivered, registration)) {
                                    break;
                                }
                            }
                        },
                        BackPressureStrategy.BUFFER)
                .emitOn(deliveryExecutor.get())
                .map(line -> {
                    undelivered.decrementAndGet();
                    return event(sse, line);
                })
                // emitOn does not cancel its upstream when the executor rejects a delivery, so the stream is also
                // released from the downstream side; release runs once, whichever termination arrives first.
                .onTermination()
                .invoke(registration::release);
    }

    int activeStreamCount() {
        return openStreams.get();
    }

    /**
     * Queues one captured line for delivery, or disconnects the stream once {@link LogTailReader#MAX_PENDING_LINES}
     * lines are waiting. Mutiny delivers an emitter failure only after the lines already queued, which a stalled client
     * never drains, so the subscription and slot are released here rather than when the failure arrives.
     */
    private static boolean offer(
            MultiEmitter<? super LogLineDto> emitter,
            LogLineDto line,
            AtomicInteger undelivered,
            StreamRegistration registration) {
        if (emitter.isCancelled()) {
            return false;
        }
        if (undelivered.incrementAndGet() > LogTailReader.MAX_PENDING_LINES) {
            registration.release();
            emitter.fail(new IllegalStateException(
                    "BootUI log-tail stream disconnected because its pending event queue is full"));
            return false;
        }
        emitter.emit(line);
        return true;
    }

    private OutboundSseEvent event(Sse sse, LogLineDto line) {
        return sse.newEventBuilder()
                .name("log")
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(reader.expose(line))
                .build();
    }

    /** One stream's slot and buffer subscription, released exactly once on whichever termination path runs first. */
    private final class StreamRegistration {

        private boolean acquired;
        private boolean released;
        private Runnable unsubscribe;

        synchronized boolean acquire() {
            if (released) {
                return false;
            }
            if (openStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                openStreams.decrementAndGet();
                return false;
            }
            acquired = true;
            return true;
        }

        void attach(Runnable unsubscribe) {
            boolean alreadyReleased;
            synchronized (this) {
                this.unsubscribe = unsubscribe;
                alreadyReleased = released;
            }
            if (alreadyReleased) {
                unsubscribe.run();
            }
        }

        void release() {
            Runnable currentUnsubscribe;
            boolean slotHeld;
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
                currentUnsubscribe = unsubscribe;
                slotHeld = acquired;
            }
            if (currentUnsubscribe != null) {
                currentUnsubscribe.run();
            }
            if (slotHeld) {
                openStreams.decrementAndGet();
            }
        }
    }
}
