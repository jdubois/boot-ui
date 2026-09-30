package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
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
 * The self-exclusion predicate reuses the adapter-wide {@link SelfTelemetryClassifier} singleton (see its
 * class javadoc) rather than a locally hardcoded path check, so this panel can never disagree with
 * Metrics/Cache/Traces about which requests are BootUI's own, and correctly honors
 * {@code bootui.monitoring.exclude-self}.
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
    private final SelfTelemetryClassifier selfClassifier;
    private final Supplier<RouteTemplateResolver> declaredRoutes;
    private final HttpExchangesService service = new HttpExchangesService();
    private final HttpRouteSummaryService routeSummary = new HttpRouteSummaryService();

    public HttpExchangesResource(
            HttpExchangeBuffer buffer, QuarkusExposurePolicy exposure, SelfTelemetryClassifier selfClassifier) {
        this(buffer, exposure, selfClassifier, null);
    }

    @Inject
    public HttpExchangesResource(
            HttpExchangeBuffer buffer,
            QuarkusExposurePolicy exposure,
            SelfTelemetryClassifier selfClassifier,
            Instance<MappingProvider> mappings) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.selfClassifier = selfClassifier;
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
        return service.report(
                buffer.snapshot(),
                selfPath(),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                declaredRoutes.get(),
                query,
                method,
                statusClass,
                route,
                offset,
                limit);
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
        return routeSummary.summarize(
                buffer.snapshot(),
                selfPath(),
                declaredRoutes.get(),
                // The buffer reports its capacity but does not count evictions.
                new HttpRouteSummaryService.ExchangeSource(buffer.capacity(), null),
                limit,
                route);
    }

    private HttpExchangesService.BootUiSelfPath selfPath() {
        return uri -> !selfClassifier.shouldInclude(selfClassifier.isBootUiPath(uri));
    }
}
