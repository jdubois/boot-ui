package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.RequestProfileAssembler;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.ArrayList;
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
 * correlated security event's principal onto its parent request as {@code securedPrincipal}.
 *
 * <p>When the optional JDBC persistence backend ({@code bootui.activity.persistence.enabled}) is on,
 * {@code QuarkusActivityCapture} owns the capture side (polling {@link #mergedReport} and appending
 * whatever it has not yet captured into the shared {@link ActivityStore} bean), and {@link #activity}
 * then serves entries and pagination from that store — which itself merges its in-memory hot cache with
 * the durable backend — instead of from a fresh live re-merge. This is entirely additive: with
 * persistence disabled (the default), {@link #persistenceSettings}{@code .enabled()} is {@code false}
 * and {@link #activity} is byte-identical to today's behavior. Unlike Spring's {@code
 * LiveActivityService.report(type, severity, since, limit)}, the shared engine
 * {@link LiveActivityAssembler} this resource calls has no type/severity/since filtering of its own, so
 * those filters apply only through the persistence query path; the KPI strip stays computed from the
 * full, unfiltered live merge either way (an "at a glance, right now" summary, not scoped to whichever
 * filter or historical page is being browsed).
 *
 * <p>The per-request <em>profile</em> drill-down ({@code GET /bootui/api/activity/request/{id}}) is a
 * <strong>reduced, trace-id-only</strong> port of Spring's fuller Symfony-style profiler: Spring's tiered
 * correlator falls back to HTTP method+path+time-window+thread heuristics when no trace id is available,
 * which relies on its synchronous one-thread-per-request servlet model and has no reliable Quarkus
 * equivalent (deliberately not ported here — see {@link RequestProfileAssembler}). A REQUEST entry from
 * {@link #activity} is marked {@code profileable} by this resource, as a thin post-processing step over the
 * shared assembler's output, iff its exchange carries a resolvable trace id — the exact (and only) signal
 * {@link #request} can correlate on; every other entry, and every request without one, stays
 * non-profileable. Spring's controller/correlator computes its own {@code profileable} semantics
 * independently and is unaffected by this adapter-only step. Read-only (the profile drill-down only reads
 * already-captured signals), plus the SSE change-notification stream {@code /stream} that ticks whenever a
 * new HTTP exchange is captured so the shared Vue panel's auto-refresh toggle works identically to Spring.
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
    private final TracesService tracesService;
    private final ActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final RequestProfileAssembler profileAssembler = new RequestProfileAssembler();
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
            QuarkusPanelAvailability panelAvailability,
            TracesService tracesService,
            ActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.sqlRecorder = sqlRecorder;
        this.exceptionStore = exceptionStore;
        this.exceptionsService = exceptionsService;
        this.securityBuffer = securityBuffer;
        this.panelAvailability = panelAvailability;
        this.tracesService = tracesService;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LiveActivityReport activity(
            @QueryParam("limit") Integer limit,
            @QueryParam("type") String type,
            @QueryParam("severity") String severity,
            @QueryParam("q") String q,
            @QueryParam("since") Long since,
            @QueryParam("until") Long until,
            @QueryParam("cursor") String cursor,
            @QueryParam("pageSize") Integer pageSize) {
        LiveActivityReport live = mergedReport(limit == null ? 0 : limit);
        if (!persistenceSettings.enabled()) {
            return live;
        }
        // Persistence enabled: the store (which itself merges its in-memory hot cache with the durable
        // backend) serves entries and pagination, so recently captured entries are visible immediately
        // and the dashboard can page back through history beyond what fits in memory. See the class
        // Javadoc for why the KPI strip above stays computed from the full, unfiltered live merge.
        ActivityQuery query = new ActivityQuery(
                persistenceSettings.instanceId(),
                type,
                severity,
                q,
                since != null && since > 0 ? since : null,
                until,
                cursor,
                pageSize == null ? 0 : pageSize);
        ActivityPage page = activityStore.query(query);
        return new LiveActivityReport(
                live.available(),
                page.entryDtos(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()));
    }

    /**
     * The merged, reverse-chronological Live Activity feed — today's entire {@link #activity} body
     * before persistence-aware pagination, extracted so {@code QuarkusActivityCapture}'s capture poller
     * can reuse it as its feed {@link java.util.function.Supplier} without duplicating the
     * signal-gathering/masking/profileable-stamping logic. Reusing this method (rather than re-reading
     * the four signal sources independently) means the poller sees the exact same self-filtered, masked
     * view the panel itself renders.
     */
    public LiveActivityReport mergedReport(int limit) {
        HttpExchangesReport requests = requestsReport();
        SqlSnapshot sql = sqlSnapshot();
        boolean securityAvailable = panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS);

        LiveActivityReport report = assembler.report(
                requests,
                sql.entries(),
                sql.available(),
                sql.unavailableWarning(),
                exceptionsService.report(exceptionStore).groups(),
                securityEvents(securityAvailable),
                securityAvailable,
                null,
                limit);

        // Adapter-side post-processing over the shared assembler's output — not a change to the engine's
        // own `profileable` default (which stays `false` for every entry it builds, unaffected by this
        // step and by extension unaffected on Spring too): a REQUEST entry is profileable here iff its
        // exchange carries a resolvable trace id, since that is the exact (and only) signal #request can
        // correlate on.
        List<ActivityEntryDto> entries = new ArrayList<>(report.entries().size());
        for (ActivityEntryDto entry : report.entries()) {
            boolean profileable = "REQUEST".equals(entry.type())
                    && entry.correlationId() != null
                    && !entry.correlationId().isBlank();
            entries.add(profileable ? withProfileable(entry) : entry);
        }
        return new LiveActivityReport(
                report.available(), entries, report.typeCounts(), report.kpis(), report.sources(), report.warnings());
    }

    /**
     * The reduced, trace-id-only per-request profile drill-down — see the class Javadoc and
     * {@link RequestProfileAssembler} for why Spring's fuller time-window/thread-heuristic tiers aren't
     * ported. Gathers the same signal sources {@link #activity} does, then delegates all correlation and
     * honest-degrade shaping to the framework-neutral assembler.
     */
    @GET
    @Path("/request/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public RequestProfileDto request(@PathParam("id") String id) {
        HttpExchangesReport requests = requestsReport();
        HttpExchangeDto request = requests.exchanges().stream()
                .filter(exchange -> id.equals(exchange.id()))
                .findFirst()
                .orElse(null);
        String traceId = request == null ? null : request.traceId();
        TraceDetailDto trace = traceId == null || traceId.isBlank()
                ? null
                : tracesService.detail(traceId).orElse(null);
        boolean securityAvailable = panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS);

        return profileAssembler.profile(
                id,
                request,
                requests.exchanges(),
                sqlSnapshot().entries(),
                allExceptionDetails(),
                securityEvents(securityAvailable),
                trace);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(sse, openStreams, MAX_CONCURRENT_STREAMS, buffer::subscribe);
    }

    private HttpExchangesReport requestsReport() {
        return exchanges.report(
                buffer.snapshot(),
                uri -> uri != null && (uri.contains("/bootui/") || uri.endsWith("/bootui")),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
    }

    private SqlSnapshot sqlSnapshot() {
        SqlTraceRecorder rec = sqlRecorder.isResolvable() ? sqlRecorder.get() : null;
        boolean available = rec != null && rec.isEnabled() && rec.hasWrappedDataSource();
        if (!available) {
            // Mirror SqlTraceResource's two-case reason: a present-but-disabled recorder is not the same as
            // an absent datasource, so don't tell the user to configure a datasource they already have.
            String warning = (rec != null && !rec.isEnabled())
                    ? "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile)."
                    : "SQL trace is unavailable until a JDBC datasource is configured.";
            return new SqlSnapshot(List.of(), false, warning);
        }
        boolean exposeParameters = rec.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return new SqlSnapshot(rec.report(exposeParameters).entries(), true, null);
    }

    private List<SecurityLogEventDto> securityEvents(boolean securityAvailable) {
        if (!securityAvailable) {
            return List.of();
        }
        int maxLogs = securityLogs.maxLogs(Integer.MAX_VALUE);
        return securityLogs
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

    /**
     * Full detail (group + every occurrence) for each currently-grouped exception, so the profile drill-down
     * can match on any occurrence's trace id — not just each group's most recent one.
     */
    private List<ExceptionDetailDto> allExceptionDetails() {
        List<ExceptionDetailDto> details = new ArrayList<>();
        for (ExceptionGroupDto group : exceptionsService.report(exceptionStore).groups()) {
            ExceptionStore.GroupDetail detail = exceptionStore.find(group.id());
            if (detail != null) {
                details.add(exceptionsService.detail(detail));
            }
        }
        return details;
    }

    private static ActivityEntryDto withProfileable(ActivityEntryDto entry) {
        return new ActivityEntryDto(
                entry.id(),
                entry.type(),
                entry.timestamp(),
                entry.severity(),
                entry.summary(),
                entry.detail(),
                entry.durationMs(),
                entry.correlationId(),
                entry.method(),
                entry.path(),
                entry.status(),
                entry.thread(),
                true,
                entry.parentId(),
                entry.securedPrincipal(),
                entry.sqlNPlusOneSuspected());
    }

    /** SQL trace snapshot for one request cycle: entries plus whether the source is present and feeding. */
    private record SqlSnapshot(List<SqlTraceEntryDto> entries, boolean available, String unavailableWarning) {}
}
