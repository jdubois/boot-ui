package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.GrpcReport;
import io.github.jdubois.bootui.engine.grpc.GrpcReportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the gRPC panel ({@code GET /bootui/api/grpc}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code GrpcController}: a thin, read-only transport adapter
 * over the shared engine {@link GrpcReportService}. There is no write path, so the resource carries no
 * {@code LocalhostGuard} write floor.</p>
 *
 * <p>The resource is produced unconditionally and the engine service is always wired (it holds no
 * {@code io.grpc} type): when {@code quarkus-grpc} is absent the metadata provider bean does not exist, the
 * service serves the stable {@code available=false} report, and the panel says so. Availability of the
 * <em>panel</em> in the manifest tracks the build-time {@code bootui.internal.grpc-present} flag (see
 * {@code QuarkusPanelAvailability}).</p>
 */
@Path("/bootui/api/grpc")
public class GrpcResource {

    private final GrpcReportService grpcReportService;

    @Inject
    public GrpcResource(GrpcReportService grpcReportService) {
        this.grpcReportService = grpcReportService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public GrpcReport grpc() {
        return grpcReportService.report();
    }
}
