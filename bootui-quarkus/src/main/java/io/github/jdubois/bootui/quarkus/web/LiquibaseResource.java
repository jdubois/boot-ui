package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseActionResponse;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Liquibase panel ({@code GET /bootui/api/liquibase/changesets},
 * {@code POST /bootui/api/liquibase/update}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code LiquibaseController}: a thin transport adapter over
 * the shared engine {@link LiquibaseService}, which owns the framework-neutral change-set assembly, ordering
 * and update orchestration. The state-changing update endpoint is gated by the shared {@code LocalhostGuard}
 * write floor enforced by {@code BootUiQuarkusSafetyFilter}; the engine maps the outcome to a
 * {@link LiquibaseActionResponse} status that is rendered here onto a JAX-RS {@link Response}, identical to the
 * Spring {@code ResponseEntity} status.</p>
 */
@Path("/bootui/api/liquibase")
public class LiquibaseResource {

    private final LiquibaseService service;

    @Inject
    public LiquibaseResource(LiquibaseService service) {
        this.service = service;
    }

    @GET
    @Path("/changesets")
    @Produces(MediaType.APPLICATION_JSON)
    public LiquibaseReport changeSets() {
        return service.report();
    }

    @POST
    @Path("/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(LiquibaseActionRequest request) {
        LiquibaseActionResponse response = service.update(request);
        return Response.status(response.status()).entity(response.body()).build();
    }
}
