package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the BootUI panel manifest ({@code GET /bootui/api/panels}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code PanelsController}. It returns the shared panel
 * registry with Quarkus-specific availability so the shared Vue UI renders an identical sidebar.</p>
 */
@Path("/bootui/api/panels")
public class PanelsResource {

    private final QuarkusPanelAvailability availability;

    @Inject
    public PanelsResource(QuarkusPanelAvailability availability) {
        this.availability = availability;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PanelsReport panels() {
        return availability.manifest();
    }
}
