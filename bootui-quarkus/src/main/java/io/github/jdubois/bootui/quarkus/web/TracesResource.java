package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the BootUI Traces panel. The Quarkus analogue of the Spring adapter's
 * {@code TracesController}: a thin delegator over the framework-neutral {@link TracesService} in
 * {@code bootui-engine}, returning the exact same {@code /bootui/api/traces} contract so the shared Vue
 * view binds unchanged.
 *
 * <p>The list and detail reads are passive. Clearing the buffer ({@code DELETE}) is the action that makes
 * this the first action-capable Quarkus panel; per-panel read-only modelling is not implemented yet, so
 * the action is always permitted (matching the Spring default where Traces is not read-only).</p>
 */
@Path("/bootui/api/traces")
public class TracesResource {

    private final TracesService service;

    @Inject
    public TracesResource(TracesService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TracesReport list(@QueryParam("limit") @DefaultValue("100") int limit) {
        return service.list(limit);
    }

    @GET
    @Path("/{traceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public TraceDetailDto detail(@PathParam("traceId") String traceId) {
        return service.detail(traceId).orElseThrow(() -> new NotFoundException("trace " + traceId + " not found"));
    }

    @DELETE
    public Response clear() {
        service.clear();
        return Response.noContent().build();
    }
}
