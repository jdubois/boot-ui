package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ConstellationReport;
import io.github.jdubois.bootui.engine.constellation.ConstellationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Constellation panel ({@code GET /bootui/api/constellation}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ConstellationController}: a thin, read-only
 * transport adapter over the shared engine {@link ConstellationService}, which fans the configured
 * {@code bootui.constellation.peers} out in parallel (bounded by a per-peer timeout) and shapes the results
 * into the {@link ConstellationReport} the UI renders. The actual peer HTTP calls are performed by
 * {@link QuarkusConstellationPeerClient}. Every call is a fresh, bounded fan-out - no data is cached across
 * requests and no background polling runs; the browser re-fetches this endpoint on its own timer, exactly
 * like the other live-data panels. Being a pure read (no state-changing method) it is not subject to the
 * cross-site write guard.</p>
 */
@Path("/bootui/api/constellation")
public class ConstellationResource {

    private final ConstellationService service;

    @Inject
    public ConstellationResource(ConstellationService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ConstellationReport constellation() {
        return service.report();
    }
}
