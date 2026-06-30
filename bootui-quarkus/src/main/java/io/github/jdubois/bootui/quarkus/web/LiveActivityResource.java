package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JAX-RS resource for the Live Activity panel ({@code GET /bootui/api/activity}). The Quarkus analogue of
 * the Spring adapter's {@code LiveActivityController}, but honestly partial: it merges the captured HTTP
 * exchanges (via the shared {@link HttpExchangeBuffer}) and JVM heap into the neutral
 * {@link LiveActivityReport}. SQL trace, exceptions and per-request profiling are not yet captured on
 * Quarkus, so those entry types/KPIs degrade cleanly and a warning is surfaced. Read-only, plus the SSE
 * change-notification stream {@code /stream} that ticks whenever a new HTTP exchange is captured so the
 * shared Vue panel's auto-refresh toggle works identically to Spring.
 */
@Path("/bootui/api/activity")
public class LiveActivityResource {

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public LiveActivityResource(HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure) {
        this.buffer = buffer;
        this.exposure = exposure;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LiveActivityReport activity(@QueryParam("limit") Integer limit) {
        HttpExchangesReport requests = exchanges.report(
                buffer.snapshot(),
                uri -> uri != null && (uri.contains("/bootui/") || uri.endsWith("/bootui")),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
        return assembler.report(requests, null, limit == null ? 0 : limit);
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
        unsubscribe.set(buffer.subscribe(() -> send(sink, sse, cleanup)));
    }

    private void send(SseEventSink sink, Sse sse, Runnable cleanup) {
        if (sink.isClosed()) {
            cleanup.run();
            return;
        }
        OutboundSseEvent event = sse.newEventBuilder()
                .name("update")
                .mediaType(MediaType.TEXT_PLAIN_TYPE)
                .data("update")
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
