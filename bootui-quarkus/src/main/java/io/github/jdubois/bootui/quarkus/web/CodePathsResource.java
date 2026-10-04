package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbeRequest;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The Code Paths panel on Quarkus ({@code docs/PLAN-v2.md} §5.14): the same engine reads as the Spring adapters, served
 * under {@code /bootui/api/code-paths}. Reads only the route and request trees this start built from the BootUI agent's
 * {@code code-paths} sensor; without the agent every read answers unavailable with the Java Agent panel's reason. Its
 * only actions are method probes (M5-8): starting and stopping one are writes that the panel's read-only policy blocks
 * before they reach this resource.
 */
@Path("/bootui/api/code-paths")
public class CodePathsResource {

    private final CodePathsService service;

    @Inject
    public CodePathsResource(CodePathsService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CodePathsReport report() {
        return service.report();
    }

    @GET
    @Path("/route")
    @Produces(MediaType.APPLICATION_JSON)
    public CodePathsRouteTreeReport route(
            @QueryParam("route") String route,
            @QueryParam("depth") Integer depth,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return service.routeTree(route, depth, offset, limit);
    }

    @GET
    @Path("/beans")
    @Produces(MediaType.APPLICATION_JSON)
    public CodePathsBeansReport beans() {
        return service.beans();
    }

    @GET
    @Path("/requests/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public CodePathsRequestTreeReport request(@PathParam("requestId") String requestId) {
        return service.requestTree(requestId);
    }

    @GET
    @Path("/probes")
    @Produces(MediaType.APPLICATION_JSON)
    public CodePathsProbesReport probes() {
        return service.probes().report();
    }

    @POST
    @Path("/probes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response startProbe(CodePathsProbeRequest request) {
        return answer(() -> service.probes().start(request == null ? null : request.method()));
    }

    @GET
    @Path("/probes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response probe(@PathParam("id") String id) {
        return answer(() -> service.probes().probe(id));
    }

    @POST
    @Path("/probes/{id}/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public Response stopProbe(@PathParam("id") String id) {
        return answer(() -> service.probes().stop(id));
    }

    @DELETE
    @Path("/probes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteProbe(@PathParam("id") String id) {
        return answer(() -> service.probes().stop(id));
    }

    /**
     * Method probes for agents: {@code start_method_probe} and {@code bootui probe start}, a refusal answered as an
     * in-band tool error with the REST status.
     */
    public CodePathsProbeDto agentStartProbe(String method) {
        return forAgents(() -> service.probes().start(method));
    }

    /** Method probes for agents: {@code get_method_probe} and {@code bootui probe show}. */
    public CodePathsProbeDto agentProbe(String id) {
        return forAgents(() -> service.probes().probe(id));
    }

    private static CodePathsProbeDto forAgents(Supplier<CodePathsProbeDto> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException ex) {
            throw new McpToolClientException(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new McpToolClientException(409, ex.getMessage());
        } catch (NoSuchElementException ex) {
            throw new McpToolClientException(404, ex.getMessage());
        }
    }

    /** The probe, or the same error statuses and bodies as the Spring adapters: 400, 409, or 404. */
    private static Response answer(Supplier<CodePathsProbeDto> call) {
        try {
            return Response.ok(call.get()).type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalArgumentException ex) {
            return error(Response.Status.BAD_REQUEST, ex);
        } catch (IllegalStateException ex) {
            return error(Response.Status.CONFLICT, ex);
        } catch (NoSuchElementException ex) {
            return error(Response.Status.NOT_FOUND, ex);
        }
    }

    private static Response error(Response.Status status, RuntimeException ex) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage()))
                .build();
    }

    /** Code Paths for agents: {@code get_code_paths} and {@code bootui code paths}. */
    public CodePathsAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }
}
