package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Health panel ({@code GET /bootui/api/health}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HealthController}: a thin, read-only transport adapter
 * over the shared engine {@link HealthService}, which owns the framework-neutral health tree shaping (the
 * "only default contributors" nudge and the DISABLED / setup-guidance rendering when no backend is present).
 * The Quarkus-specific SmallRye mapping lives behind the {@code HealthProvider} the service reads. Being a pure
 * read (no state-changing method) it is not subject to the cross-site write guard.</p>
 */
@Path("/bootui/api/health")
public class HealthResource {

    private final HealthService health;

    @Inject
    public HealthResource(HealthService health) {
        this.health = health;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthNodeDto health() {
        return health.health();
    }
}
