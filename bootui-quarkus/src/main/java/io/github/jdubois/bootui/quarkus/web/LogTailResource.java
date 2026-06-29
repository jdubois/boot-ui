package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    public void stream(@Context SseEventSink sink, @Context Sse sse) {
        if (openStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
            openStreams.decrementAndGet();
            sink.close();
            return;
        }
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Runnable> unsubscribe = new AtomicReference<>(() -> {});
        Runnable cleanup = () -> {
            if (done.compareAndSet(false, true)) {
                unsubscribe.get().run();
                openStreams.decrementAndGet();
            }
        };
        LogTailBuffer.Subscription subscription = buffer.subscribeWithReplay(line -> send(sink, sse, line, cleanup));
        unsubscribe.set(subscription.unsubscribe());
        for (LogLineDto line : subscription.backlog()) {
            if (done.get()) {
                break;
            }
            send(sink, sse, line, cleanup);
        }
    }

    private void send(SseEventSink sink, Sse sse, LogLineDto line, Runnable cleanup) {
        if (sink.isClosed()) {
            cleanup.run();
            return;
        }
        OutboundSseEvent event = sse.newEventBuilder()
                .name("log")
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(line)
                .build();
        try {
            sink.send(event).exceptionally(error -> {
                cleanup.run();
                return null;
            });
        } catch (RuntimeException ex) {
            cleanup.run();
        }
    }
}
