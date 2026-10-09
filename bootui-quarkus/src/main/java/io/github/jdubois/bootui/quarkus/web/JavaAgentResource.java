package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorSwitchRequest;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * The Java Agent panel on Quarkus ({@code docs/PLAN-v2.md} §5.13): the same engine report as the Spring adapters,
 * served at {@code GET /bootui/api/java-agent}, which only reads the bootstrap bridge's status. {@code POST
 * /bootui/api/java-agent/sensors/{id}} switches a switchable sensor on or off at run time (M5-14), an action the panel's
 * read-only policy refuses like every other, with the Spring adapters' statuses: 400 for another sensor, 409 when it
 * cannot be switched.
 */
@Path("/bootui/api/java-agent")
public class JavaAgentResource {

    private final JavaAgentService service;

    @Inject
    public JavaAgentResource(JavaAgentService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public JavaAgentReport report() {
        return service.report();
    }

    @POST
    @Path("/sensors/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response switchSensor(@PathParam("id") String id, JavaAgentSensorSwitchRequest request) {
        try {
            if (request == null || request.enabled() == null) {
                throw new IllegalArgumentException(
                        "The request body must say {\"enabled\": true} or {\"enabled\": false}.");
            }
            return Response.ok(service.switchSensor(id, request.enabled()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (IllegalArgumentException ex) {
            return error(Response.Status.BAD_REQUEST, ex);
        } catch (IllegalStateException ex) {
            return error(Response.Status.CONFLICT, ex);
        }
    }

    private static Response error(Response.Status status, RuntimeException ex) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage()))
                .build();
    }
}
