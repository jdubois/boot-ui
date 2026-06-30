package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the framework-neutral shell-chrome endpoint
 * ({@code GET /bootui/api/overview}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code OverviewController} GET: it returns the
 * high-level {@link OverviewDto} the shared shell binds its header to (and which primes the CSRF
 * cookie). The shell needs this data on every platform, independently of the client-side Overview
 * dashboard panel (which aggregates the advisor endpoints in the browser and never calls this
 * endpoint). It is passive (read-only).</p>
 */
@Path("/bootui/api/overview")
public class OverviewResource {

    private final QuarkusApplicationInfo info;

    @Inject
    public OverviewResource(QuarkusApplicationInfo info) {
        this.info = info;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public OverviewDto overview() {
        return info.overview();
    }
}
