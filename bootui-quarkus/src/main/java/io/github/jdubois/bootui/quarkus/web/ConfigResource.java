package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Configuration panel ({@code GET /bootui/api/config}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ConfigController}: a thin transport adapter over the
 * shared engine {@link ConfigService}, which masks, sorts, filters and pages the raw entries supplied by
 * {@code QuarkusConfigProvider}. The Spring runtime-override write path is intentionally absent — overrides
 * are a Spring-bootstrap concept, so the panel is reported read-only on Quarkus.</p>
 */
@Path("/bootui/api/config")
public class ConfigResource {

    private final ConfigService configService;

    @Inject
    public ConfigResource(ConfigService configService) {
        this.configService = configService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ConfigReport list(
            @QueryParam("q") String query,
            @QueryParam("source") String source,
            @QueryParam("overridesOnly") boolean overridesOnly,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return configService.list(query, source, overridesOnly, offset, limit);
    }
}
