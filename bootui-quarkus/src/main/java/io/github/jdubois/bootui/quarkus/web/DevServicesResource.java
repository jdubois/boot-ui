package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.DevServicesReport;
import io.github.jdubois.bootui.engine.devservices.DevServicesReportService;
import io.github.jdubois.bootui.spi.DevServicesProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the Dev Services panel ({@code GET /bootui/api/dev-services}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code DevServicesController}: a thin transport over the
 * shared engine {@link DevServicesReportService}, which sorts and wraps the build-time-captured services from
 * {@code QuarkusDevServicesProvider}. Quarkus exposes Dev Services only at build time, so the log-tail and
 * restart actions Spring offers are unavailable here — they respond {@code 409 Conflict} with an honest
 * reason rather than 404, matching the panel id contract.</p>
 */
@Path("/bootui/api/dev-services")
public class DevServicesResource {

    private final DevServicesReportService reportService;

    private final DevServicesProvider provider;

    @Inject
    public DevServicesResource(DevServicesReportService reportService, DevServicesProvider provider) {
        this.reportService = reportService;
        this.provider = provider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DevServicesReport list() {
        return reportService.report(provider);
    }

    @GET
    @Path("/{id}/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logs(@PathParam("id") String id) {
        throw new WebApplicationException(
                "Quarkus manages Dev Services container logs; BootUI shows a build-time snapshot only.",
                Response.Status.CONFLICT);
    }

    @POST
    @Path("/{id}/restart")
    @Produces(MediaType.APPLICATION_JSON)
    public Response restart(@PathParam("id") String id) {
        throw new WebApplicationException(
                "Quarkus manages Dev Services lifecycle; BootUI cannot restart them.", Response.Status.CONFLICT);
    }
}
