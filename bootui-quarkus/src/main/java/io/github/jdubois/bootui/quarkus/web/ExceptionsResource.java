package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JAX-RS resource for the Exceptions panel: {@code GET /bootui/api/exceptions} (grouped report),
 * {@code GET /bootui/api/exceptions/{id}} (detail), {@code DELETE} (clear), and the SSE stream
 * {@code /stream}. The Quarkus analogue of the Spring adapter's {@code ExceptionsController}: a thin
 * transport over the shared engine {@link ExceptionStore} and {@link ExceptionsService}, fed on this
 * platform by {@code QuarkusExceptionLogHandler} (logged throwables) and {@code QuarkusExceptionCaptureFilter}
 * (unhandled web failures). Both adapters serve the identical wire so the shared Vue panel renders the same.
 */
@Path("/bootui/api/exceptions")
public class ExceptionsResource {

    /** Upper bound on simultaneous exception streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final ExceptionStore store;
    private final ExceptionsService service;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public ExceptionsResource(ExceptionStore store, ExceptionsService service) {
        this.store = store;
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ExceptionsReport list() {
        return service.report(store);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ExceptionDetailDto detail(@PathParam("id") String id) {
        ExceptionStore.GroupDetail detail = store.find(id);
        if (detail == null) {
            throw new NotFoundException("exception " + id + " not found");
        }
        return service.detail(detail);
    }

    @DELETE
    public Response clear() {
        store.clear();
        return Response.noContent().build();
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
        unsubscribe.set(store.subscribe(() -> send(sink, sse, cleanup)));
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
