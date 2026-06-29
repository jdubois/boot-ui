package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the HTTP Exchanges panel ({@code GET /bootui/api/http-exchanges}). The Quarkus
 * analogue of the Spring adapter's {@code HttpExchangesController}: a thin transport adapter over the
 * shared engine {@link HttpExchangesService}, which owns masking, trace-id extraction, self-exclusion
 * and paging. The capture source is the Quarkus-only {@link HttpExchangeBuffer} fed by
 * {@link QuarkusHttpExchangeCaptureFilter} (Spring keeps Actuator's repository), so the wire is identical.
 *
 * <p>Read-only — no state-changing endpoints, hence no write gate.</p>
 */
@Path("/bootui/api/http-exchanges")
public class HttpExchangesResource {

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final HttpExchangesService service = new HttpExchangesService();

    @Inject
    public HttpExchangesResource(HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure) {
        this.buffer = buffer;
        this.exposure = exposure;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HttpExchangesReport exchanges(
            @QueryParam("q") String query,
            @QueryParam("method") String method,
            @QueryParam("statusClass") String statusClass,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        return service.report(
                buffer.snapshot(),
                uri -> uri != null && (uri.contains("/bootui/") || uri.endsWith("/bootui")),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                query,
                method,
                statusClass,
                offset,
                limit);
    }
}
