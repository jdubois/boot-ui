package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JAX-RS resource for the Log Tail panel ({@code GET /bootui/api/log-tail/recent} and the SSE stream
 * {@code GET /bootui/api/log-tail/stream}). The Quarkus analogue of the Spring adapter's
 * {@code LogTailController}: a thin transport over the shared engine {@link LogTailBuffer}, fed on this
 * platform by {@code QuarkusLogTailHandler}. Both adapters serve the identical wire (an {@code "log"}
 * event carrying a {@code LogLineDto}) so the shared Vue panel renders the same.
 */
@Path("/bootui/api/log-tail")
public class LogTailResource {

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final LogTailBuffer buffer;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public LogTailResource(LogTailBuffer buffer) {
        this.buffer = buffer;
    }

    @GET
    @Path("/recent")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LogLineDto> recent() {
        return buffer.recent();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return Multi.createFrom()
                .<OutboundSseEvent>emitter(
                        emitter -> {
                            if (openStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                                openStreams.decrementAndGet();
                                emitter.complete();
                                return;
                            }
                            // Atomically snapshot the backlog and register the live subscriber under one
                            // lock, then replay the backlog: no line is dropped or duplicated across the
                            // replay/live boundary. Live delivery arrives on arbitrary logging threads, so
                            // BUFFER (a multi-producer queue with a single serialised drain) is required for
                            // thread-safe emission — and, unlike the tick streams, log lines must not be
                            // dropped, which BUFFER also guarantees.
                            LogTailBuffer.Subscription subscription = buffer.subscribeWithReplay(line -> {
                                if (!emitter.isCancelled()) {
                                    emitter.emit(event(sse, line));
                                }
                            });
                            emitter.onTermination(() -> {
                                subscription.unsubscribe().run();
                                openStreams.decrementAndGet();
                            });
                            for (LogLineDto line : subscription.backlog()) {
                                if (emitter.isCancelled()) {
                                    break;
                                }
                                emitter.emit(event(sse, line));
                            }
                        },
                        BackPressureStrategy.BUFFER);
    }

    private static OutboundSseEvent event(Sse sse, LogLineDto line) {
        return sse.newEventBuilder()
                .name("log")
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(line)
                .build();
    }
}
