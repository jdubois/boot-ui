package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Thin MySQL transport binding. The engine owns the sanitized cache and single-flight admission shared
 * by REST and MCP. GET never discovers datasources or opens connections; only the explicit blocking
 * action performs JDBC work. The extension's dev/test indexing gate keeps this resource dark in production.
 */
@ApplicationScoped
@Path("/bootui/api/mysql")
public class MySqlResource {

    private final MySqlInsightService service;

    @Inject
    public MySqlResource(MySqlInsightService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public MySqlInsightReport mysql() {
        return service.report();
    }

    @POST
    @Path("/read")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public MySqlInsightReport read() {
        return service.read();
    }
}
