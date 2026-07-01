package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ProfilesReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Profile Diff panel ({@code GET /bootui/api/profile-diff}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ProfileDiffController}: a thin transport adapter
 * over the shared engine {@link ConfigService}, which masks and groups the profile-specific entries supplied
 * by {@code QuarkusConfigProvider} (built from the active {@code %profile.}-prefixed MicroProfile keys).</p>
 */
@Path("/bootui/api/profile-diff")
public class ProfileDiffResource {

    private final ConfigService configService;

    @Inject
    public ProfileDiffResource(ConfigService configService) {
        this.configService = configService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ProfilesReport profiles() {
        return configService.profiles();
    }
}
