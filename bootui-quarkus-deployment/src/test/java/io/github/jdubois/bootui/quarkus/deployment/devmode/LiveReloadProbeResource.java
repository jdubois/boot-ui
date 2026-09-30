package io.github.jdubois.bootui.quarkus.deployment.devmode;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** An application endpoint {@link BootUiLiveReloadRunIdentityTest} edits to trigger a live reload. */
@Path("/live-reload-probe")
public class LiveReloadProbeResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String probe() {
        return "before";
    }
}
