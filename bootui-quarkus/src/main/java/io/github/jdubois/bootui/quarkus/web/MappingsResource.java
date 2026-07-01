package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Mappings panel, the Quarkus analogue of the Spring adapter's
 * {@code MappingsController}. A thin, read-only transport adapter over the shared engine
 * {@link MappingsService}, which sorts, free-text queries and pages the flattened, self-filtered route
 * mappings supplied by the (Quarkus) {@code MappingProvider}.
 *
 * <p>The BootUI UI consumes {@code GET /bootui/api/mappings/flat} (the same path it uses on Spring). The
 * root {@code GET /bootui/api/mappings} returns the same {@link MappingsReport} — on Spring that path is a
 * raw Actuator descriptor passthrough the UI does not use, but Quarkus has no equivalent raw descriptor, so
 * serving the neutral report keeps the panel's primary GET answering 200 + JSON (the cross-adapter
 * conformance contract) without inventing a second shape.</p>
 *
 * <p>There is no write path, so the resource carries no {@code LocalhostGuard} write floor. The engine
 * service is always wired (it holds no RESTEasy types); when the build-time capture is absent the provider
 * reports unavailable and the engine renders an empty report.</p>
 */
@Path("/bootui/api/mappings")
public class MappingsResource {

    private final MappingsService mappingsService;

    @Inject
    public MappingsResource(MappingsService mappingsService) {
        this.mappingsService = mappingsService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public MappingsReport mappings(
            @QueryParam("q") String query, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return mappingsService.report(query, offset, limit);
    }

    @GET
    @Path("/flat")
    @Produces(MediaType.APPLICATION_JSON)
    public MappingsReport flatMappings(
            @QueryParam("q") String query, @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        return mappingsService.report(query, offset, limit);
    }
}
