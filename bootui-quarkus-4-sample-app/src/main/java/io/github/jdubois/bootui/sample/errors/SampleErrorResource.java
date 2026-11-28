package io.github.jdubois.bootui.sample.errors;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Endpoints that fail on purpose so the sample application demonstrates a real error contract: one
 * failure handled by the global {@code @Provider} mapper, and one handled by this resource's own
 * {@code @ServerExceptionMapper}, which the catalogue reports as resource-scoped.
 *
 * <p>Nothing fails on page load — each failure is triggered explicitly by calling its endpoint.</p>
 */
@Path("/api/errors")
@Produces(MediaType.APPLICATION_JSON)
public class SampleErrorResource {

    @GET
    @Path("/not-found")
    public String notFound() {
        throw new SampleProductNotFoundException("No product with id 4711");
    }

    @GET
    @Path("/local")
    public String local() {
        throw new SampleProductRejectedException("Product 4711 was rejected by the catalogue rules");
    }

    @ServerExceptionMapper
    public RestResponse<SampleErrorBody> handleLocally(SampleProductRejectedException exception) {
        return RestResponse.status(
                RestResponse.Status.CONFLICT, new SampleErrorBody("product_rejected", exception.getMessage()));
    }
}
