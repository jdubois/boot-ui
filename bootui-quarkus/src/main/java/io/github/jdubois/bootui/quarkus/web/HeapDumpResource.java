package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Heap Dump panel ({@code GET /bootui/api/heap-dump}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HeapDumpController} GET: a passive read of the
 * current capture status and value-free class histogram from the shared engine {@link HeapDumpService}.
 * The mutating capture/analyze/delete actions and the raw {@code .hprof} download are intentionally
 * deferred until the Quarkus read-only/action gating is in place.</p>
 */
@Path("/bootui/api/heap-dump")
public class HeapDumpResource {

    private final HeapDumpService service;

    @Inject
    public HeapDumpResource(HeapDumpService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport report(
            @QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("smartFilter") @DefaultValue("") String smartFilter) {
        return service.report(filter, smartFilter);
    }
}
