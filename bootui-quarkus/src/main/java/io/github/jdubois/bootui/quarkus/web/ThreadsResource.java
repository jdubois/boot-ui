package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Threads panel ({@code GET /bootui/api/threads}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ThreadDumpController} GET: it returns a
 * filtered, paged snapshot of the JVM's live threads from the shared engine {@link ThreadDumpService}
 * and is passive (read-only). The mutating raw-dump download is intentionally deferred until the
 * Quarkus read-only/action gating is in place.</p>
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
}
