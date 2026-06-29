package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Live Activity panel ({@code GET /bootui/api/activity}). The Quarkus analogue of
 * the Spring adapter's {@code LiveActivityController}, but honestly partial: it merges the captured HTTP
 * exchanges (via the shared {@link HttpExchangeBuffer}) and JVM heap into the neutral
 * {@link LiveActivityReport}. SQL trace, exceptions and per-request profiling are not yet captured on
 * Quarkus, so those entry types/KPIs degrade cleanly and a warning is surfaced. Read-only.
 */
@Path("/bootui/api/activity")
public class LiveActivityResource {

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();

    @Inject
    public LiveActivityResource(HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure) {
        this.buffer = buffer;
        this.exposure = exposure;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LiveActivityReport activity(@QueryParam("limit") Integer limit) {
        HttpExchangesReport requests = exchanges.report(
                buffer.snapshot(),
                uri -> uri != null && (uri.contains("/bootui/") || uri.endsWith("/bootui")),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
        return assembler.report(requests, null, limit == null ? 0 : limit);
    }
}
