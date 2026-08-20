package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.GrpcReport;
import io.github.jdubois.bootui.engine.grpc.GrpcReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-neutral gRPC controller shared by the servlet and WebFlux adapters. It serves
 * {@code GET /bootui/api/grpc} by delegating to the engine {@link GrpcReportService}.
 *
 * <p>The controller is registered unconditionally so the panel always answers with a well-formed contract:
 * when no gRPC integration is present the engine returns the stable {@code available=false} report with an
 * honest reason, which is what the UI renders. It carries no {@code io.grpc} import, so the endpoint never
 * triggers optional-dependency class loading; all such types stay inside the gated
 * {@code SpringGrpcMetadataProvider}.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/grpc")
public class GrpcController {

    private final GrpcReportService grpcReportService;

    public GrpcController(GrpcReportService grpcReportService) {
        this.grpcReportService = grpcReportService;
    }

    @GetMapping
    public GrpcReport grpc() {
        return grpcReportService.report();
    }
}
