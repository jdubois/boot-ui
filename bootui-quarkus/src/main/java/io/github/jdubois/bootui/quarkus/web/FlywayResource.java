package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.engine.flyway.FlywayActionResponse;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Flyway panel ({@code GET /bootui/api/flyway/migrations},
 * {@code POST /bootui/api/flyway/migrate}, {@code POST /bootui/api/flyway/clean}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code FlywayController}: a thin transport adapter over the
 * shared engine {@link FlywayService}, which owns the framework-neutral report assembly and the
 * {@code migrate}/{@code clean} orchestration (target resolution, confirmation gating, clean-disabled gating).
 * The state-changing endpoints are gated by the shared {@code LocalhostGuard} write floor enforced by
 * {@code BootUiQuarkusSafetyFilter}; the engine maps each outcome to a {@link FlywayActionResponse} status that
 * is rendered here onto a JAX-RS {@link Response}, identical to the Spring {@code ResponseEntity} status.</p>
 */
@Path("/bootui/api/flyway")
public class FlywayResource {

    private final FlywayService service;

    @Inject
    public FlywayResource(FlywayService service) {
        this.service = service;
    }

    @GET
    @Path("/migrations")
    @Produces(MediaType.APPLICATION_JSON)
    public FlywayReport migrations() {
        return service.report();
    }

    @POST
    @Path("/migrate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response migrate(FlywayActionRequest request) {
        FlywayActionResponse response = service.migrate(request);
        return Response.status(response.status()).entity(response.body()).build();
    }

    @POST
    @Path("/clean")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response clean(FlywayActionRequest request) {
        FlywayActionResponse response = service.clean(request);
        return Response.status(response.status()).entity(response.body()).build();
    }
}
