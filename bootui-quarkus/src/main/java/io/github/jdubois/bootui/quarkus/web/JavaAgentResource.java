package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The Java Agent panel on Quarkus ({@code docs/PLAN-v2.md} §5.13): the same engine report as the Spring adapters,
 * served at {@code GET /bootui/api/java-agent}. A pure read of the bootstrap bridge's status: it claims, installs, and
 * sends nothing.
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
}
