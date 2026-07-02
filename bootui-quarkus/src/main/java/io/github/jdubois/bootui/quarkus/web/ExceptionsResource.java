package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionStatusUpdateRequest;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Changes the triage status of one exception group ({@code OPEN}/{@code ACKNOWLEDGED}/
     * {@code RESOLVED}). See {@link ExceptionsService#updateStatus} for validation and regression
     * semantics; mirrors the Spring controller's status codes and JSON error body exactly.
     */
    @POST
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateStatus(@PathParam("id") String id, ExceptionStatusUpdateRequest request) {
        try {
            ExceptionGroupDto updated = service.updateStatus(store, id, request == null ? null : request.status());
            if (updated == null) {
                throw new NotFoundException("exception " + id + " not found");
            }
            return Response.ok(updated).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, store::subscribe);
    }
}
