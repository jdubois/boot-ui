package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * JAX-RS resource for the Metrics panel ({@code GET /bootui/api/metrics},
 * {@code GET /bootui/api/metrics/detail}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code MetricsController}: a thin transport adapter over
 * the shared engine {@link MetricsReportProvider}, which owns the framework-neutral Micrometer reading,
 * grouping, tag aggregation, self-meter filtering and the {@code key:value} tag-filter parsing. A malformed
 * tag filter is rejected by the engine with {@link IllegalArgumentException}, mapped here to a 400 with the
 * same JSON {@code {"error": ...}} body the Spring controller returns.</p>
 */
@Path("/bootui/api/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {

    private final MetricsReportProvider provider;

    @Inject
    public MetricsResource(MetricsReportProvider provider) {
        this.provider = provider;
    }

    @GET
    public MetricsReport metrics() {
        return provider.metrics();
    }

    @GET
    @Path("/detail")
    public Response metric(@QueryParam("name") String name, @QueryParam("tag") List<String> tagFilters) {
        try {
            return Response.ok(provider.metric(name, tagFilters)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()))
                    .build();
        }
    }
}
