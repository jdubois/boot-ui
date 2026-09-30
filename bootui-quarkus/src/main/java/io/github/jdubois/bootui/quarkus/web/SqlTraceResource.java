package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlRequestEvidence;
import io.github.jdubois.bootui.engine.sqltrace.SqlRouteAttribution.Correlation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceInsightsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JAX-RS resource for the SQL Trace panel ({@code GET /bootui/api/sql-trace} plus {@code /clear} and
 * {@code /recording} actions). The Quarkus analogue of Spring's {@code SqlTraceController}: a thin transport
 * adapter over the shared engine {@link SqlTraceRecorder}, which owns the capped buffer, grouping/stats/N+1
 * assembly and report shaping, so the wire is byte-identical. Capture is the Quarkus-only Agroal datasource
 * wrap from {@code BootUiSqlTraceProducer}; bind values surface only when capture is on and value exposure is
 * not metadata-only. The recorder is resolved through an {@link Instance} so the panel renders unavailable
 * when no JDBC datasource is present (AGROAL gate). State-changing endpoints are gated by the panel-access
 * filter when the panel is read-only. The SSE change-notification stream {@code /stream} ticks whenever a new
 * statement is recorded (or recording is toggled/cleared) so the shared Vue panel's auto-refresh toggle works
 * identically to Spring; it closes immediately when no recorder is present.
 */
@Path("/bootui/api/sql-trace")
public class SqlTraceResource {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    /** Upper bound on simultaneous SQL-trace streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /**
     * Quarkus serves a request on a Vert.x event loop or a worker thread it may hand off between, so it
     * has no one-thread-per-request invariant to correlate on. Attribution therefore relies on trace
     * context first, with a uniqueness-guarded time window as the only fallback, and says so on the wire.
     */
    private static final Set<Correlation> CORRELATIONS = Set.of(Correlation.TRACE_ID, Correlation.TIME_WINDOW);

    private final Instance<SqlTraceRecorder> recorder;
    private final Instance<HttpExchangeBuffer> exchanges;
    private final Instance<MappingProvider> mappings;
    private final QuarkusExposurePolicy exposure;
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public SqlTraceResource(
            Instance<SqlTraceRecorder> recorder,
            Instance<HttpExchangeBuffer> exchanges,
            Instance<MappingProvider> mappings,
            QuarkusExposurePolicy exposure) {
        this.recorder = recorder;
        this.exchanges = exchanges;
        this.mappings = mappings;
        this.exposure = exposure;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport trace() {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        return rec == null ? SqlTraceReport.unavailable(NOT_CONFIGURED) : report(rec);
    }

    /**
     * Slow-statement rankings and per-route database attribution over the retained trace window, computed
     * by the same engine services the Spring bindings use so the JSON is identical.
     *
     * <p>Requests come from the HTTP exchange buffer this adapter already fills — no extra capture is
     * added. RESTEasy Reactive registers a single catch-all Vert.x route rather than one route per JAX-RS
     * method, so no route template is available at capture time; instead the captured path is matched
     * against the application's declared {@code @Path} routes, which the Mappings panel already reports
     * from the build-time index. A path no declared route matches falls back to a masked path, and the
     * response says which of the two it used through {@code routeSource}.</p>
     */
    @GET
    @Path("/insights")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceInsightsReport insights() {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return SqlTraceInsightsReport.unavailable(NOT_CONFIGURED);
        }
        return new SqlTraceInsightsService(rec).insights(requestEvidence(), CORRELATIONS, routeTemplates());
    }

    /**
     * The captured exchanges, reduced to the fields attribution may use. An exchange with no timestamp
     * cannot bound a window and is dropped rather than defaulted to "now", which would fabricate overlap.
     */
    private List<SqlRequestEvidence> requestEvidence() {
        if (!exchanges.isResolvable()) {
            return List.of();
        }
        List<CapturedHttpExchange> captured = exchanges.get().snapshot().stream()
                .filter(exchange -> exchange.timestamp() != null)
                .toList();
        List<SqlRequestEvidence> evidence = new ArrayList<>(captured.size());
        for (int index = 0; index < captured.size(); index++) {
            evidence.add(toEvidence(captured.get(index), index));
        }
        return List.copyOf(evidence);
    }

    /**
     * The application's declared JAX-RS routes, captured at build time for the Mappings panel, so a route
     * ranking can be labelled with a declared template instead of a masked path. An absent or unavailable
     * provider — production, or a build without the mappings build step — simply means no templates.
     */
    private RouteTemplateResolver routeTemplates() {
        return DeclaredRouteTemplates.from(mappings);
    }

    private static SqlRequestEvidence toEvidence(CapturedHttpExchange exchange, int ordinal) {
        long start = exchange.timestamp().toEpochMilli();
        long end = start + (exchange.durationMs() == null ? 0 : Math.max(0, exchange.durationMs()));
        String path = exchange.uri() == null ? null : exchange.uri().getPath();
        return SqlRequestEvidence.of(ordinal, exchange.method(), path, null, exchange.traceId(), start, end, null);
    }

    @POST
    @Path("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport clear() {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        rec.clear();
        return report(rec);
    }

    @POST
    @Path("/recording")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport recording(SqlTraceRecordingRequest request) {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !rec.isRecording() : request.enabled();
        rec.setRecording(enabled);
        return report(rec);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return Multi.createFrom().<OutboundSseEvent>empty();
        }
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, rec::subscribe);
    }

    private SqlTraceReport report(SqlTraceRecorder rec) {
        if (!rec.hasWrappedDataSource()) {
            return SqlTraceReport.unavailable(unavailableReason(rec));
        }
        boolean exposeParameters = rec.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return rec.report(exposeParameters);
    }

    private String unavailableReason(SqlTraceRecorder rec) {
        if (!rec.isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile).";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }
}
