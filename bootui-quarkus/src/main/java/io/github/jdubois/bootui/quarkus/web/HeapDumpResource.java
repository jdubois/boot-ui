package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Heap Dump panel ({@code GET /bootui/api/heap-dump} plus the
 * {@code capture}/{@code analyze}/{@code delete}/{@code download} actions).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HeapDumpController}: a thin transport adapter
 * over the shared engine {@link HeapDumpService}, which owns the framework-neutral capture, histogram,
 * eviction and masking logic. Reads are passive and cheap. Capture, analyze and delete are mutating
 * actions exposed via {@code POST}, so they are gated by the shared {@code LocalhostGuard} write floor
 * enforced by {@code BootUiQuarkusSafetyFilter}, exactly like every other state-changing panel action.
 * Capture forces a full GC and writes an {@code .hprof}, so the actions and the streaming download run
 * on a worker thread ({@link Blocking}) rather than the Vert.x event loop. The raw download is disabled
 * by default and returns 404 unless explicitly enabled, because the dump file contains unmasked
 * secrets.</p>
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

    @POST
    @Blocking
    @Path("/capture")
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport capture(@QueryParam("live") @DefaultValue("true") boolean live) {
        return service.capture(live);
    }

    @POST
    @Blocking
    @Path("/analyze")
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport analyze() {
        return service.analyze();
    }

    @POST
    @Blocking
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport delete(@FormParam("name") String name) {
        return service.delete(name);
    }

    @GET
    @Blocking
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@QueryParam("name") String name) {
        if (!service.rawDownloadAllowed()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        java.nio.file.Path file = service.resolveExisting(name);
        if (file == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(file.toFile())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .type(MediaType.APPLICATION_OCTET_STREAM)
                .build();
    }
}
