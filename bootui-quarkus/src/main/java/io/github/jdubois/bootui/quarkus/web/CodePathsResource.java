package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The Code Paths panel on Quarkus ({@code docs/PLAN-v2.md} §5.14): the same engine reads as the Spring adapters, served
 * under {@code /bootui/api/code-paths}. Reads only the route and request trees this start built from the BootUI agent's
 * {@code code-paths} sensor; without the agent every read answers unavailable with the Java Agent panel's reason.
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

    /** Code Paths for agents: {@code get_code_paths} and {@code bootui code paths}. */
    public CodePathsAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }
}
