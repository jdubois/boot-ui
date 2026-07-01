package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Threads panel ({@code GET /bootui/api/threads} plus the raw-dump download).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ThreadDumpController}: {@code GET} returns a
 * filtered, paged snapshot of the JVM's live threads from the shared engine {@link ThreadDumpService}
 * and is passive. The raw text dump is exposed as a {@code POST} so it is treated as an explicit,
 * state-changing action gated by the shared {@code LocalhostGuard} write floor enforced by
 * {@code BootUiQuarkusSafetyFilter}; it runs on a worker thread ({@link Blocking}) since it walks every
 * live stack.</p>
 */
@Path("/bootui/api/threads")
public class ThreadsResource {

    private final ThreadDumpService service;

    @Inject
    public ThreadsResource(ThreadDumpService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadDumpReport threads(
            @QueryParam("q") String query,
            @QueryParam("state") String state,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return service.report(query, state, offset, limit);
    }

    @POST
    @Blocking
    @Path("/download")
    @Produces(MediaType.TEXT_PLAIN)
    public Response download() {
        String dump = service.rawDump();
        if (dump == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dump)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"thread-dump.txt\"")
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
