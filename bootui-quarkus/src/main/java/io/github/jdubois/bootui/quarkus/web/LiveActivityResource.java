package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.activity.ActivityCaptureFactory;
import io.github.jdubois.bootui.engine.activity.ActivityCapturePoller;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.RequestProfileAssembler;
import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

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
 * <p>The optional JDBC persistence backend ({@code bootui.activity.persistence.enabled}) is served by the
 * shared {@link SwitchableActivityStore} bean, which always exists (even with persistence disabled, as a
 * bare in-memory store) so this resource can always inject it directly. {@code QuarkusActivityCapture}
 * owns the capture side (polling {@link #mergedReport} and appending whatever it has not yet captured into
 * the store), and {@link #activity} branches on the store's own live {@link SwitchableActivityStore#persistent()}
 * state — not the static startup settings — so it correctly reflects a runtime switch: when persistent, the
 * store (which itself merges its in-memory hot cache with the durable backend) serves entries and
 * pagination instead of a fresh live re-merge. This is entirely additive: with persistence never enabled
 * or switched on, {@link #activity} is byte-identical to today's behavior. Unlike Spring's {@code
 * LiveActivityService.report(type, severity, since, limit)}, the shared engine
 * {@link LiveActivityAssembler} this resource calls has no type/severity/since filtering of its own, so
 * those filters apply only through the persistence query path; the KPI strip stays computed from the
 * full, unfiltered live merge either way (an "at a glance, right now" summary, not scoped to whichever
 * filter or historical page is being browsed).
 *
 * <p>{@link #useExistingDatasource} hot-switches Live Activity from in-memory to durable JDBC persistence
 * by reusing the host application's own {@code DataSource} — no restart required — mirroring the Spring
 * adapter's identically named controller action. On success it starts its own capture poller against the
 * newly durable store (held in {@link #switchPoller}, independent of {@code QuarkusActivityCapture}'s own
 * poller field: the two poller-creation paths are mutually exclusive, since a switch only succeeds when
 * the store was not already persistent, which is exactly the condition under which
 * {@code QuarkusActivityCapture}'s startup poller would not have been created) and closes it on
 * {@link #onStop}.
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
    private final ScheduledTaskRunStore scheduledTaskRunStore;
    private final QuarkusPanelAvailability panelAvailability;
    private final TracesService tracesService;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final Instance<DataSource> dataSources;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final RequestProfileAssembler profileAssembler = new RequestProfileAssembler();
    private final SecurityLogsService securityLogs = new SecurityLogsService();
    private final AtomicInteger openStreams = new AtomicInteger();
    private volatile ActivityCapturePoller switchPoller;

    @Inject
    public LiveActivityResource(
            HttpExchangeBuffer buffer,
            QuarkusExposurePolicy exposure,
            Instance<SqlTraceRecorder> sqlRecorder,
            ExceptionStore exceptionStore,
            ExceptionsService exceptionsService,
            SecurityEventBuffer securityBuffer,
            ScheduledTaskRunStore scheduledTaskRunStore,
            QuarkusPanelAvailability panelAvailability,
            TracesService tracesService,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            Instance<DataSource> dataSources) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.sqlRecorder = sqlRecorder;
        this.exceptionStore = exceptionStore;
        this.exceptionsService = exceptionsService;
        this.securityBuffer = securityBuffer;
        this.scheduledTaskRunStore = scheduledTaskRunStore;
        this.panelAvailability = panelAvailability;
        this.tracesService = tracesService;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.dataSources = dataSources;
    }

    /**
     * Stops {@link #switchPoller} (making one last synchronous capture pass first, so entries produced
     * since the last tick aren't dropped) when persistence was hot-switched on at runtime. Independent of
     * {@code QuarkusActivityCapture}'s own {@code ShutdownEvent} observer, which stops its own poller
     * (started only when persistence was already enabled at startup) and closes the shared
     * {@link SwitchableActivityStore} bean itself; the two never both hold a live poller, since a switch
     * only succeeds when the store was not already persistent.
     */
    void onStop(@Observes ShutdownEvent event) {
        ActivityCapturePoller poller = switchPoller;
        if (poller != null) {
            poller.close();
            switchPoller = null;
        }
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
        ActivityPersistenceOptionDto persistenceOption = new ActivityPersistenceOptionDto(
                activityStore.persistent(),
                BootUiEngineProducer.resolveDataSource(dataSources) != null,
                persistenceSettings.tableName());
        if (!activityStore.persistent()) {
            return new LiveActivityReport(
                    live.available(),
                    live.entries(),
                    live.typeCounts(),
                    live.kpis(),
                    live.sources(),
                    live.warnings(),
                    null,
                    persistenceOption);
        }
        // Persistence active: the store (which itself merges its in-memory hot cache with the durable
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
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistenceOption);
    }

    /**
     * Hot-switches Live Activity from in-memory to durable JDBC persistence, reusing the host
     * application's own {@code DataSource} — no restart required. Gated by explicit confirmation (this
     * creates a database table and starts writing to it) and by the shared {@code LocalhostGuard} write
     * floor enforced by {@code BootUiQuarkusSafetyFilter}, like every other mutating panel action.
     * Idempotent: calling this when persistence is already active is a no-op that reports success rather
     * than an error. On success, starts this resource's own capture poller against the newly durable
     * store, exactly as {@code QuarkusActivityCapture}'s {@code onStart} would have done had persistence
     * been enabled from startup.
     */
    @POST
    @Path("/use-existing-datasource")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response useExistingDatasource(ActivitySwitchRequest request) {
        DataSource dataSource = BootUiEngineProducer.resolveDataSource(dataSources);
        ActivitySwitchResponse response = new ActivitySwitchService()
                .useExistingDataSource(activityStore, persistenceSettings, dataSource, request);
        if (response.newSettings() != null) {
            switchPoller = ActivityCaptureFactory.start(
                    activityStore, response.newSettings(), () -> mergedReport(0).entries());
        }
        return Response.status(response.status()).entity(response.body()).build();
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
                scheduledTaskRunStore.runs(),
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
        return SseStreams.updates(
                sse,
                openStreams,
                MAX_CONCURRENT_STREAMS,
                combined(buffer::subscribe, scheduledTaskRunStore::subscribe));
    }

    /**
     * Combines two {@link SseStreams.ChangeSource}s into one that notifies {@code onChange} when either
     * fires, so the merged Live Activity stream ticks on a new HTTP exchange <em>or</em> a new captured
     * {@code @Scheduled} execution — mirroring the Spring adapter, whose single {@code BootUiChangeStream}
     * already fans in every signal source (including {@link ScheduledTaskRunStore}) to the same effect.
     */
    private static SseStreams.ChangeSource combined(SseStreams.ChangeSource first, SseStreams.ChangeSource second) {
        return onChange -> {
            Runnable unsubscribeFirst = first.subscribe(onChange);
            Runnable unsubscribeSecond = second.subscribe(onChange);
            return () -> {
                unsubscribeFirst.run();
                unsubscribeSecond.run();
            };
        };
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
