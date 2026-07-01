package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the HTTP Probe panel ({@code POST /bootui/api/http-probe}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HttpProbeController}: it delegates to the shared
 * engine {@link HttpProbeService}, which sends the request to the application's own loopback address and
 * returns a sanitized {@link HttpProbeResponse}. The engine always targets
 * {@code http://localhost:<port><path>} (the port coming from {@code QuarkusServerPortSupplier}), so the
 * probe can never reach an external host regardless of the supplied path. This is a state-changing
 * ({@code POST}) endpoint, so it is gated by {@code BootUiQuarkusSafetyFilter} like every other write.</p>
 */
@Path("/bootui/api/http-probe")
public class HttpProbeResource {

    private final HttpProbeService probeService;

    @Inject
    public HttpProbeResource(HttpProbeService probeService) {
        this.probeService = probeService;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpProbeResponse probe(HttpProbeRequest request) {
        return probeService.probe(request);
    }
}
