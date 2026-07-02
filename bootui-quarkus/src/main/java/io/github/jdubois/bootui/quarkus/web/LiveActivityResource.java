package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JAX-RS resource for the Live Activity panel ({@code GET /bootui/api/activity}). The Quarkus analogue of
 * the Spring adapter's {@code LiveActivityController}: it merges the four signals captured on this platform
 * — HTTP exchanges (via the shared {@link HttpExchangeBuffer}), SQL trace (via the shared
 * {@link SqlTraceRecorder}), exceptions (via the shared {@link ExceptionStore}), and security/audit events
 * (via the shared {@link SecurityEventBuffer}) — plus JVM heap into the neutral {@link LiveActivityReport}.
 * SQL trace contributes only when a datasource is configured (the recorder is gated on Agroal); security
 * events contribute only when Quarkus's security capability is present and
 * {@code quarkus.security.events.enabled=true} (the same gate {@code SecurityLogsResource} uses, reused here
 * via {@link QuarkusPanelAvailability}); when either is absent the assembler surfaces a warning (SQL) or
 * simply omits the source (security) and its entries are omitted. Signal-to-request correlation is
 * data-driven on the OpenTelemetry trace id when present: each captured signal is stamped with the active
 * span's trace id (see {@code QuarkusOtelTraceIdProvider}), and the engine {@link LiveActivityAssembler}
 * nests SQL/exception/security entries under the request sharing that trace id, also stamping a uniquely
 * correlated security event's principal onto its parent request as {@code securedPrincipal}. The per-request
 * <em>profile</em> drill-down (flipping {@code profileable}) remains Spring-only. Read-only, plus the SSE
 * change-notification stream {@code /stream} that ticks whenever a new HTTP exchange is captured so the
 * shared Vue panel's auto-refresh toggle works identically to Spring.
 */
@Path("/bootui/api/activity")
public class LiveActivityResource {

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final Instance<SqlTraceRecorder> sqlRecorder;
    private final ExceptionStore exceptionStore;
    private final ExceptionsService exceptionsService;
    private final SecurityEventBuffer securityBuffer;
    private final QuarkusPanelAvailability panelAvailability;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final SecurityLogsService securityLogs = new SecurityLogsService();
    private final AtomicInteger openStreams = new AtomicInteger();

    @Inject
    public LiveActivityResource(
            HttpExchangeBuffer buffer,
            QuarkusExposurePolicy exposure,
            Instance<SqlTraceRecorder> sqlRecorder,
            ExceptionStore exceptionStore,
            ExceptionsService exceptionsService,
            SecurityEventBuffer securityBuffer,
            QuarkusPanelAvailability panelAvailability) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.sqlRecorder = sqlRecorder;
        this.exceptionStore = exceptionStore;
        this.exceptionsService = exceptionsService;
        this.securityBuffer = securityBuffer;
        this.panelAvailability = panelAvailability;
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

        SqlTraceRecorder rec = sqlRecorder.isResolvable() ? sqlRecorder.get() : null;
        boolean sqlAvailable = rec != null && rec.isEnabled() && rec.hasWrappedDataSource();
        List<SqlTraceEntryDto> sqlEntries = List.of();
        String sqlUnavailableWarning = null;
        if (sqlAvailable) {
            boolean exposeParameters =
                    rec.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
            sqlEntries = rec.report(exposeParameters).entries();
        } else {
            // Mirror SqlTraceResource's two-case reason: a present-but-disabled recorder is not the same as an
            // absent datasource, so don't tell the user to configure a datasource they already have.
            sqlUnavailableWarning = (rec != null && !rec.isEnabled())
                    ? "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile)."
                    : "SQL trace is unavailable until a JDBC datasource is configured.";
        }

        boolean securityAvailable = panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS);
        List<SecurityLogEventDto> securityEvents = List.of();
        if (securityAvailable) {
            int maxLogs = securityLogs.maxLogs(Integer.MAX_VALUE);
            securityEvents = securityLogs
                    .report(
                            securityBuffer.snapshot(),
                            maxLogs,
                            exposure.maskSecrets(),
                            exposure.valueExposure(),
                            null,
                            null,
                            null,
                            null,
                            null)
                    .events();
        }

        return assembler.report(
                requests,
                sqlEntries,
                sqlAvailable,
                sqlUnavailableWarning,
                exceptionsService.report(exceptionStore).groups(),
                securityEvents,
                securityAvailable,
                null,
                limit == null ? 0 : limit);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, buffer::subscribe);
    }
}
