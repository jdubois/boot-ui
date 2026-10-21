package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.cli.CliToolResponse;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpPayloads;
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
 * Quarkus transport for the command-line endpoint at {@code /bootui/api/cli} — the JAX-RS analogue of the
 * Spring adapter's {@code BootUiCliController}, answering the same statuses over the same {@link CliService}.
 *
 * <p>{@code GET} describes the tools this instance advertises; {@code POST /tools/{name}} invokes one and
 * returns its payload directly, with the outcome in the HTTP status so shells and CI jobs can branch on it.
 * The route sits under {@code /bootui/api}, so {@code BootUiQuarkusSafetyFilter}'s loopback, Host allow-list,
 * and cross-site-write defenses apply unchanged.
 */
@Path("/bootui/api/cli")
public class BootUiCliResource {

    private final CliService service;
    private final ObjectMapper mapper;

    @Inject
    public BootUiCliResource(CliService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CliServerStatus status() {
        return service.status();
    }

    /**
     * Invokes one tool. The body is bound as a raw map, not a fixed argument record, so that every property the
     * caller sent reaches {@link CliService} and an argument the tool does not declare is refused rather than
     * dropped during binding.
     */
    @POST
    @Path("/tools/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response invoke(@PathParam("name") String name, Map<String, Object> arguments) {
        CliToolResponse response = service.invoke(name, arguments);
        return Response.status(response.status().code())
                .entity(
                        response.successful()
                                ? QuarkusMcpPayloads.render(response.payload(), mapper)
                                : Map.of("error", response.error()))
                .build();
    }
}
