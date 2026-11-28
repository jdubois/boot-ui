package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Fault Tolerance panel ({@code GET /bootui/api/fault-tolerance}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code FaultToleranceController}: a thin, read-only transport
 * adapter over the shared engine {@link FaultToleranceService}, which orders, caps and wraps the policies supplied
 * by the (Quarkus) {@code FaultTolerancePolicyProvider} plus the bounded capture buffer. There is no write path —
 * opening or resetting a circuit breaker is explicitly out of scope — so the resource carries no
 * {@code LocalhostGuard} write floor.</p>
 *
 * <p>The resource is produced unconditionally and the engine service is always wired (it holds no
 * fault-tolerance types): when {@code quarkus-smallrye-fault-tolerance} is absent the provider reports
 * unavailable and the engine renders an empty report with {@code faultTolerancePresent=false}. Availability of
 * the <em>panel</em> in the manifest, by contrast, tracks the build-time
 * {@code bootui.internal.fault-tolerance-present} flag (see {@code QuarkusPanelAvailability}).</p>
 */
@Path("/bootui/api/fault-tolerance")
public class FaultToleranceResource {

    private final FaultToleranceService faultToleranceService;

    @Inject
    public FaultToleranceResource(FaultToleranceService faultToleranceService) {
        this.faultToleranceService = faultToleranceService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public FaultToleranceReport faultTolerance() {
        return faultToleranceService.report();
    }
}
