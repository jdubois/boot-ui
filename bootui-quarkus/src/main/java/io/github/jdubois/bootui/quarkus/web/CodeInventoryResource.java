package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.CodeInventoryAgentReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependenciesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodsReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryReport;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The Code Inventory panel on Quarkus ({@code docs/PLAN-v2.md} §5.15): the same engine reads as the Spring adapters,
 * served under {@code /bootui/api/code-inventory}. Reads only what the BootUI agent's inventory sensor recorded and the
 * scan of the application's class files its run started; without the agent every read answers unavailable with the
 * Java Agent panel's reason.
 */
@Path("/bootui/api/code-inventory")
public class CodeInventoryResource {

    private final CodeInventoryService service;

    @Inject
    public CodeInventoryResource(CodeInventoryService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CodeInventoryReport report() {
        return service.report();
    }

    @GET
    @Path("/changes")
    @Produces(MediaType.APPLICATION_JSON)
    public CodeInventoryChangesReport changes(
            @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return service.changes(offset, limit);
    }

    @GET
    @Path("/methods")
    @Produces(MediaType.APPLICATION_JSON)
    public CodeInventoryMethodsReport methods(
            @QueryParam("package") String packageName,
            @QueryParam("class") String className,
            @QueryParam("status") String status,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return service.methods(packageName, className, status, offset, limit);
    }

    @GET
    @Path("/dependencies")
    @Produces(MediaType.APPLICATION_JSON)
    public CodeInventoryDependenciesReport dependencies(
            @QueryParam("status") String status,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return service.dependencies(status, offset, limit);
    }

    /** Code Inventory for agents: {@code get_code_inventory} and {@code bootui code inventory}. */
    public CodeInventoryAgentReport agentReport(String query, Integer limit) {
        return service.agentReport(query, limit);
    }
}
