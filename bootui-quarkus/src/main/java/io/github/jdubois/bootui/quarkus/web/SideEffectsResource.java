package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * The Side Effects panel on Quarkus ({@code docs/PLAN-v2.md} §5.16, M5-5a): the same engine reads as the Spring
 * adapters, served under {@code /bootui/api/side-effects}. Reads only the rows this start recorded from the BootUI
 * agent's side-effect sensors; without the agent every read answers unavailable with the Java Agent panel's reason. An
 * unknown sensor answers {@code 400} with the engine's message.
 */
@Path("/bootui/api/side-effects")
public class SideEffectsResource {

    private final SideEffectsService service;

    @Inject
    public SideEffectsResource(SideEffectsService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SideEffectsReport report() {
        return service.report();
    }

    @GET
    @Path("/sensor")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sensor(
            @QueryParam("sensor") String sensor,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        try {
            return Response.ok(service.sensor(sensor, offset, limit)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()))
                    .build();
        }
    }

    /** Side Effects for agents: {@code get_side_effects} and {@code bootui side-effects}. */
    public SideEffectsAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }
}
