package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.HttpRouteSummaryService;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.function.Supplier;

/**
 * JAX-RS resource for the HTTP Exchanges panel ({@code GET /bootui/api/http-exchanges}). The Quarkus
 * analogue of the Spring adapter's {@code HttpExchangesController}: a thin transport adapter over the
 * shared engine {@link HttpExchangesService}, which owns masking, trace-id extraction, self-exclusion
 * and paging. The capture source is the Quarkus-only {@link HttpExchangeBuffer} fed by
 * {@link QuarkusHttpExchangeCaptureFilter} (Spring keeps Actuator's repository), so the wire is identical.
 * BootUI's own requests never reach the buffer: the capture filter skips them on the path below the Quarkus root
 * path, so no second check runs here on the absolute URL, which could only hide application requests whose path
 * merely contains the BootUI mount. The exchange list and the route rankings carry the buffer's retention counts,
 * taken from the same snapshot as the exchanges: the list's {@code retention} object, and the route window's buffer
 * size and evictions.
 *
 * <p>Routes are resolved from the application's declared JAX-RS mappings (see {@link DeclaredRouteTemplates}),
 * then a masked path, exactly as SQL Trace route attribution resolves them on this adapter.</p>
 *
 * <p>Read-only — no state-changing endpoints, hence no write gate.</p>
 */
@Path("/bootui/api/http-exchanges")
public class HttpExchangesResource {

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final Supplier<RouteTemplateResolver> declaredRoutes;
    private final HttpExchangesService service = new HttpExchangesService();
    private final HttpRouteSummaryService routeSummary = new HttpRouteSummaryService();

    public HttpExchangesResource(HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure) {
        this(buffer, exposure, null);
    }

    @Inject
    public HttpExchangesResource(
            HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure, Instance<MappingProvider> mappings) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.declaredRoutes = DeclaredRouteTemplates.caching(mappings);
    }

    /** The exchange list without a route filter, for programmatic callers such as MCP. */
    public HttpExchangesReport exchanges(
            String query, String method, String statusClass, Integer offset, Integer limit) {
        return exchanges(query, method, statusClass, null, offset, limit);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HttpExchangesReport exchanges(
            @QueryParam("q") String query,
            @QueryParam("method") String method,
            @QueryParam("statusClass") String statusClass,
            @QueryParam("route") String route,
            @QueryParam("offset") Integer offset,
            @QueryParam("limit") Integer limit) {
        TieredCaptureBuffer.Snapshot<CapturedHttpExchange> snapshot = buffer.retainedSnapshot();
        return service.report(
                snapshot.newestFirst(),
                SELF_EXCLUDED_AT_CAPTURE,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                declaredRoutes.get(),
                query,
                method,
                statusClass,
                route,
                offset,
                limit,
                snapshot.retention(buffer.slowThresholdMillis()));
    }

    /** Route rankings without a pinned route, for programmatic callers such as MCP. */
    public HttpRoutesReport routes(Integer limit) {
        return routes(limit, null);
    }

    /**
     * Route performance rankings over the retained exchanges ({@code GET .../http-exchanges/routes}).
     * {@code limit} is the number of routes each ranking criterion contributes, and {@code route} names a
     * route whose row is included whatever its rank, so a link to it always finds it.
     */
    @GET
    @Path("/routes")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpRoutesReport routes(@QueryParam("limit") Integer limit, @QueryParam("route") String route) {
        TieredCaptureBuffer.Snapshot<CapturedHttpExchange> snapshot = buffer.retainedSnapshot();
        return routeSummary.summarize(
                snapshot.newestFirst(),
                SELF_EXCLUDED_AT_CAPTURE,
                declaredRoutes.get(),
                new HttpRouteSummaryService.ExchangeSource(snapshot.capacity(), snapshot.evicted()),
                limit,
                route);
    }

    /** The capture filter never records BootUI's own requests, judged below the Quarkus root path. */
    private static final HttpExchangesService.BootUiSelfPath SELF_EXCLUDED_AT_CAPTURE =
            HttpExchangesService.BootUiSelfPath.EXCLUDED_AT_CAPTURE;
}
