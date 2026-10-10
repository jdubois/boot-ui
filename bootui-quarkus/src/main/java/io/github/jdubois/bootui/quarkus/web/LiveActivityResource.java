package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcesDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalActivityCapture;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalRowDetails;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RequestProfileSelection;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities;
import io.github.jdubois.bootui.engine.web.ProfileEvidence;
import io.github.jdubois.bootui.engine.web.ProfileEvidence.Source;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;

/**
 * JAX-RS resource for the Live Activity panel ({@code GET /bootui/api/activity}). The Quarkus analogue of
 * the Spring adapter's {@code LiveActivityController}: it merges the signals captured on this platform —
 * HTTP exchanges (via the shared {@link HttpExchangeBuffer}), SQL trace (via the shared
 * {@link SqlTraceRecorder}), exceptions (via the shared {@link ExceptionStore}), security/audit events
 * (via the shared {@link SecurityEventBuffer}), scheduled-task runs (via the shared
 * {@link ScheduledTaskRunStore}), Kafka messages (via the shared {@link KafkaActivityRecorder}), and
 * captured email (via the shared {@link EmailCaptureService}), REST Client Reactive calls (via the shared
 * {@link RestClientTraceRecorder}), and JVM heap into the neutral {@link LiveActivityReport}. Cache activity
 * has no capture seam on Quarkus yet. The HTTP-exchange source never holds BootUI's own traffic, because the capture
 * filter skips it on the path below the Quarkus root path. SQL trace
 * contributes only when a datasource is configured (the recorder is gated on Agroal); security events
 * contribute only when Quarkus's security capability is present and
 * {@code quarkus.security.events.enabled=true} (the same gate {@code SecurityLogsResource} uses, reused here
 * via {@link QuarkusPanelAvailability}); when either is absent the assembler surfaces a warning (SQL) or
 * simply omits the source (security) and its entries are omitted. Signal-to-request correlation is
 * data-driven on the OpenTelemetry trace id when present: each captured signal is stamped with the active
 * span's trace id (see {@code QuarkusOtelTraceIdSource}), and the engine {@link LiveActivityAssembler}
 * nests SQL/exception/security/MAIL entries under the request sharing that trace id, also stamping a uniquely
 * correlated security event's principal onto its parent request as {@code securedPrincipal}.
 *
 * <p>The optional JDBC persistence backend ({@code bootui.activity.persistence.enabled}) is served by the
 * shared {@link SwitchableActivityStore} bean, which always exists (even with persistence disabled, as a
 * bare in-memory store) so this resource can always inject it directly. {@code QuarkusActivityCapture}
 * owns the startup journal subscriber, and {@link #activity} branches on the store's own live {@link SwitchableActivityStore#persistent()}
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
 * adapter's identically named controller action. On success it starts its own journal capture against the
 * candidate durable store before publishing it (held in {@link #switchCapture}, independent of {@code QuarkusActivityCapture}'s own
 * capture field: the two capture-creation paths are mutually exclusive, since a switch only succeeds when
 * the store was not already persistent, which is exactly the condition under which
 * {@code QuarkusActivityCapture}'s startup capture would not have been created) and closes it on
 * {@link #onStop}.
 *
 * <p>The per-request <em>profile</em> drill-down ({@code GET /bootui/api/activity/request/{id}}) is served
 * by the shared {@link ExecutionProfileAssembler} with <strong>trace-id-only</strong> capabilities: Spring
 * MVC also correlates by serving thread and time window, which relies on its synchronous
 * one-thread-per-request servlet model and has no reliable Quarkus equivalent, so this resource reports
 * those tiers unavailable rather than inferring them. A REQUEST entry from
 * {@link #activity} is marked {@code profileable} through the engine's shared
 * {@link LiveActivityAssembler#withExactProfiles} iff its exchange carries BootUI's request id or a resolvable
 * trace id — the exact signals {@link #request} can correlate on; every other entry, and every request with
 * neither, stays non-profileable. Spring MVC's controller/correlator computes its own {@code profileable}
 * semantics independently. Read-only (the profile drill-down only reads
 * already-captured signals), plus the SSE change-notification stream {@code /stream} that ticks whenever
 * any merged source changes (a new HTTP exchange, a captured {@code @Scheduled} execution, a Kafka message,
 * or a captured email) so the shared Vue panel's auto-refresh toggle works identically to Spring.
 */
@Path("/bootui/api/activity")
public class LiveActivityResource {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(LiveActivityResource.class);

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /** Why the profile's cache section is empty on Quarkus: {@code quarkus-cache} has no capture seam. */
    static final String CACHE_UNAVAILABLE = "Cache access capture is not available on Quarkus: quarkus-cache "
            + "interceptors expose no public seam to observe cache accesses.";

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final Instance<SqlTraceRecorder> sqlRecorder;
    private final ExceptionStore exceptionStore;
    private final ExceptionsService exceptionsService;
    private final Instance<EmailCaptureService> emailCaptureService;
    private final SecurityEventBuffer securityBuffer;
    private final ScheduledTaskRunStore scheduledTaskRunStore;
    private final QuarkusPanelAvailability panelAvailability;
    private final TracesService tracesService;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final Instance<DataSource> dataSources;
    private Instance<RuntimeJournal> journal;
    private Instance<JournalAggregates> journalAggregates;
    private Instance<AgentEvidence> agentEvidence;
    private final KafkaActivityRecorder kafkaRecorder;
    private final RabbitActivityRecorder rabbitRecorder;
    private final FaultToleranceEventRecorder faultToleranceRecorder;
    private final RestClientTraceRecorder restClientTraceRecorder;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler;
    private final ReservedActivityEntries reservedEntries;
    private volatile ExecutionProfileAssembler profileAssembler = new ExecutionProfileAssembler();
    private volatile Instance<JavaAgentService> javaAgent;
    private final SecurityLogsService securityLogs = new SecurityLogsService();
    private final AtomicInteger openStreams = new AtomicInteger();
    private volatile ActivityCapture switchCapture;
    private Supplier<RouteTemplateResolver> declaredRoutes = RouteTemplateResolver::empty;
    private ActivityFeedSource feedSource = ActivityFeedSource.DEFAULT;

    @Inject
    public LiveActivityResource(
            HttpExchangeBuffer buffer,
            QuarkusExposurePolicy exposure,
            Instance<SqlTraceRecorder> sqlRecorder,
            ExceptionStore exceptionStore,
            ExceptionsService exceptionsService,
            Instance<EmailCaptureService> emailCaptureService,
            SecurityEventBuffer securityBuffer,
            ScheduledTaskRunStore scheduledTaskRunStore,
            QuarkusPanelAvailability panelAvailability,
            TracesService tracesService,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            Instance<DataSource> dataSources,
            KafkaActivityRecorder kafkaRecorder,
            RabbitActivityRecorder rabbitRecorder,
            FaultToleranceEventRecorder faultToleranceRecorder,
            RestClientTraceRecorder restClientTraceRecorder) {
        this.buffer = buffer;
        // The exchange buffer carries bootui.activity.request-slow-threshold-ms, so REQUEST severity and exchange
        // retention classify slow requests identically.
        this.assembler = new LiveActivityAssembler(buffer.slowThresholdMillis());
        this.reservedEntries = new ReservedActivityEntries(buffer.slowThresholdMillis());
        this.exposure = exposure;
        this.sqlRecorder = sqlRecorder;
        this.exceptionStore = exceptionStore;
        this.exceptionsService = exceptionsService;
        this.emailCaptureService = emailCaptureService;
        this.securityBuffer = securityBuffer;
        this.scheduledTaskRunStore = scheduledTaskRunStore;
        this.panelAvailability = panelAvailability;
        this.tracesService = tracesService;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.dataSources = dataSources;
        this.kafkaRecorder = kafkaRecorder;
        this.rabbitRecorder = rabbitRecorder;
        this.faultToleranceRecorder = faultToleranceRecorder;
        this.restClientTraceRecorder = restClientTraceRecorder;
    }

    /**
     * The application's declared JAX-RS routes, so each REQUEST's exchange — and the slowest-request KPI — is
     * labelled with the same route the HTTP Exchanges route summary uses. An initializer rather than a
     * constructor parameter, because it is optional evidence: without it, routes fall back to masked paths.
     */
    @Inject
    void setMappings(Instance<MappingProvider> mappings) {
        this.declaredRoutes = DeclaredRouteTemplates.caching(mappings);
    }

    /**
     * {@code bootui.activity.feed-source}, the same key and default as on Spring ({@code docs/PLAN-v2.md} §5.3). An
     * unknown source fails startup rather than silently serving another feed.
     */
    @Inject
    void setFeedSource(Config config) {
        this.feedSource = BootUiEngineProducer.activityFeedSource(config);
        this.maxHandoff = config.getOptionalValue("bootui.agent.executors.max-handoff", java.time.Duration.class)
                .orElse(null);
        this.profileAssembler = new ExecutionProfileAssembler(
                SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD,
                ExecutionProfileAssembler.DEFAULT_MAX_CHILDREN_PER_SECTION,
                HandoffWindow.millis(maxHandoff));
    }

    /** The Java Agent service, which says whether the request profile's {@code PROPAGATED} tier applies (M5-2). */
    @Inject
    void setJavaAgent(Instance<JavaAgentService> javaAgent) {
        this.javaAgent = javaAgent;
    }

    private String propagationUnavailableReason() {
        try {
            Instance<JavaAgentService> agent = javaAgent;
            return agent == null || !agent.isResolvable()
                    ? ProfileCapabilities.PROPAGATION_REASON
                    : agent.get().propagationUnavailableReason();
        } catch (RuntimeException ex) {
            return ProfileCapabilities.PROPAGATION_REASON;
        }
    }

    private java.time.Duration maxHandoff;

    /** The runtime journal whose status block and <b>Clear recording</b> this panel serves. */
    @Inject
    void setRuntimeJournal(Instance<RuntimeJournal> journal, Instance<JournalAggregates> journalAggregates) {
        this.journal = journal;
        this.journalAggregates = journalAggregates;
    }

    /**
     * The BootUI agent's evidence kept outside the journal (M5-11), which the journal status reports beside its own bytes
     * and which every clear of the journal clears.
     */
    @Inject
    void setAgentEvidence(Instance<AgentEvidence> agentEvidence) {
        this.agentEvidence = agentEvidence;
    }

    /**
     * Stops {@link #switchCapture} (processing what the journal already recorded first, so entries produced
     * since the last tick aren't dropped) when persistence was hot-switched on at runtime. Independent of
     * {@code QuarkusActivityCapture}'s own {@code ShutdownEvent} observer, which stops its own capture
     * (started only when persistence was already enabled at startup) and closes the shared
     * {@link SwitchableActivityStore} bean itself; the two never both hold a live capture, since a switch
     * only succeeds when the store was not already persistent.
     */
    void onStop(@Observes ShutdownEvent event) {
        synchronized (activityStore) {
            journal = null;
            ActivityCapture capture = switchCapture;
            if (capture != null) {
                capture.close();
                switchCapture = null;
            }
        }
    }

    /** The feed from the configured source, without the journal-only filters; for callers such as the MCP bridge. */
    public LiveActivityReport activity(
            Integer limit,
            String type,
            String severity,
            String q,
            Long since,
            Long until,
            String cursor,
            Integer pageSize) {
        return activity(limit, type, severity, q, since, until, cursor, pageSize, null, null, null, null, false);
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
            @QueryParam("pageSize") Integer pageSize,
            @QueryParam("source") String source,
            @QueryParam("route") String route,
            @QueryParam("run") String run,
            @QueryParam("requestId") String requestId,
            @QueryParam("noRequest") boolean noRequest) {
        LiveActivityReport live = live(
                source,
                new JournalActivityFeed.Filter(
                        type, severity, since == null ? 0 : since, route, run, requestId, noRequest),
                limit == null ? 0 : limit);
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
        JournalTextExposure storedRule = JournalTextExposure.of(exposure);
        ActivityPage page = io.github.jdubois.bootui.engine.activity.ReadableActivityPages.query(
                activityStore,
                query,
                row -> JournalSourcePanels.isReadable(
                        row,
                        panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel)),
                storedRule::reapply);
        return new LiveActivityReport(
                live.available(),
                // Stored rows were written under MASKED (or raw, by an older build) while their panel was enabled;
                // the live panel gate and exposure policy apply on read, and a search matches only what they show.
                page.entryDtos(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistenceOption);
    }

    /** The runtime journal's status block ({@code docs/PLAN-v2.md} §5.2). */
    @GET
    @Path("/journal")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeJournalStatusDto journal() {
        return runtimeJournal().status();
    }

    /** The run's resource track and CPU ledger ({@code docs/PLAN-v2.md} §5.11). */
    @GET
    @Path("/resources")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeResourcesDto resources() {
        return runtimeJournal().resources();
    }

    /** <b>Clear recording</b>: drops the run's recorded events and aggregates, when confirmed. */
    @POST
    @Path("/journal/clear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearJournal(RuntimeJournalClearRequest request) {
        RuntimeJournalService.Response response = runtimeJournal().clear(request);
        return Response.status(response.status()).entity(response.body()).build();
    }

    /**
     * The live feed from the requested source, or the configured one ({@code docs/PLAN-v2.md} §5.3). An unknown source
     * is rejected, never silently replaced.
     */
    private LiveActivityReport live(String source, JournalActivityFeed.Filter filter, int limit) {
        ActivityFeedSource resolved;
        try {
            resolved = ActivityFeedSource.parse(source, feedSource);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
        JournalActivityReports reports = new JournalActivityReports(
                journal != null && journal.isResolvable() ? journal.get() : null,
                buffer.slowThresholdMillis(),
                SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD,
                declaredRoutes,
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel),
                exposure);
        // With the journal disabled or absent, the panel buffers serve the feed rather than leaving it empty.
        if (resolved == ActivityFeedSource.JOURNAL && reports.recording()) {
            return reports.report(
                    filter,
                    limit,
                    null,
                    JournalRowDetails.of(
                            requestsReport().exchanges(),
                            panelVisible(BootUiPanels.EXCEPTIONS)
                                    ? exceptionsService.report(exceptionStore).groups()
                                    : null,
                            emailReport() == null ? null : emailReport().messages(),
                            securityEvents(panelVisible(BootUiPanels.SECURITY_LOGS))));
        }
        LiveActivityReport live = mergedReport(limit);
        boolean journalAsked = resolved == ActivityFeedSource.JOURNAL && source != null && !source.isBlank();
        if (!journalAsked && !JournalActivityReports.hasJournalOnlyFilter(filter)) {
            return live;
        }
        List<String> warnings = new ArrayList<>(live.warnings());
        if (journalAsked) {
            warnings.add(JournalActivityReports.JOURNAL_UNAVAILABLE);
        }
        if (JournalActivityReports.hasJournalOnlyFilter(filter)) {
            warnings.add(JournalActivityReports.JOURNAL_FILTERS_IGNORED);
        }
        return new LiveActivityReport(
                live.available(), live.entries(), live.typeCounts(), live.kpis(), live.sources(), warnings);
    }

    private RuntimeJournalService runtimeJournal() {
        return new RuntimeJournalService(
                journal != null && journal.isResolvable() ? journal.get() : null,
                journalAggregates != null && journalAggregates.isResolvable() ? journalAggregates.get() : null,
                agentEvidence != null && agentEvidence.isResolvable() ? agentEvidence.get() : null);
    }

    /**
     * Hot-switches Live Activity from in-memory to durable JDBC persistence, reusing the host
     * application's own {@code DataSource} — no restart required. Gated by explicit confirmation (this
     * creates a database table and starts writing to it) and by the shared {@code LocalhostGuard} write
     * floor enforced by {@code BootUiQuarkusSafetyFilter}, like every other mutating panel action.
     * Idempotent: calling this when persistence is already active is a no-op that reports success rather
     * than an error. On success, starts the journal subscriber against the newly durable
     * store, exactly as {@code QuarkusActivityCapture}'s {@code onStart} would have done had persistence
     * been enabled from startup.
     */
    @POST
    @Path("/use-existing-datasource")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response useExistingDatasource(ActivitySwitchRequest request) {
        DataSource dataSource = BootUiEngineProducer.resolveDataSource(dataSources);
        synchronized (activityStore) {
            RuntimeJournal current = journal != null && journal.isResolvable() ? journal.get() : null;
            ActivitySwitchResponse response = new ActivitySwitchService()
                    .useExistingDataSource(
                            activityStore,
                            persistenceSettings,
                            dataSource,
                            request,
                            current,
                            (target, settings) -> startCapture(target, settings, current));
            if (response.capture() != null) {
                switchCapture = response.capture();
            }
            return Response.status(response.status()).entity(response.body()).build();
        }
    }

    /**
     * Starts writing Live Activity's durable history from the same source the feed reads ({@code docs/PLAN-v2.md}
     * §5.3): the runtime journal's subscriber, whatever source the feed reads, or nothing, with a warning, when the
     * journal is disabled; 2.0.0 removed the poller of {@link #mergedReport}. Shared by {@code QuarkusActivityCapture} at startup and by the
     * runtime switch; the caller owns closing the returned capture.
     */
    public ActivityCapture startPersistence(ActivityStore store, ActivityPersistenceSettings settings) {
        RuntimeJournal current = journal != null && journal.isResolvable() ? journal.get() : null;
        if (current != null && current.settings().enabled()) {
            return startCapture(store, settings, current);
        }
        LOG.warn("Live Activity persistence is enabled, but the runtime journal is disabled"
                + " (bootui.runtime-journal.enabled=false), so no durable history is written: the journal is its only"
                + " source in 2.0.");
        return null;
    }

    private ActivityCapture startCapture(
            ActivityStore target, ActivityPersistenceSettings settings, RuntimeJournal journal) {
        return JournalActivityCapture.start(
                target,
                settings,
                reservedEntries,
                journal,
                new JournalActivityFeed(
                        buffer.slowThresholdMillis(), SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD, declaredRoutes),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel));
    }

    /**
     * The merged, reverse-chronological Live Activity feed read from the panel buffers, today's entire {@link
     * #activity} body before persistence-aware pagination when the feed source is {@code buffers}.
     */
    public LiveActivityReport mergedReport(int limit) {
        HttpExchangesReport requests = requestsReport();
        SqlSnapshot sql = sqlSnapshot();
        boolean securityAvailable = panelVisible(BootUiPanels.SECURITY_LOGS);
        boolean kafkaAvailable = panelAvailability.isPanelAvailable(BootUiPanels.KAFKA)
                && panelAvailability.isPanelEnabled(BootUiPanels.KAFKA)
                && kafkaRecorder.isEnabled();
        boolean rabbitAvailable = panelAvailability.isPanelAvailable(BootUiPanels.RABBITMQ)
                && panelAvailability.isPanelEnabled(BootUiPanels.RABBITMQ)
                && rabbitRecorder.isEnabled();
        EmailsReport emailReport = emailReport();
        boolean emailAvailable = emailReport != null;

        boolean restClientAvailable = restClientActivityAvailable();
        boolean faultToleranceAvailable = panelAvailability.isPanelAvailable(BootUiPanels.FAULT_TOLERANCE)
                && panelAvailability.isPanelEnabled(BootUiPanels.FAULT_TOLERANCE)
                && faultToleranceRecorder.isEnabled();

        LiveActivityReport report = assembler.report(
                requests,
                sql.entries(),
                sql.available(),
                sql.unavailableWarning(),
                panelVisible(BootUiPanels.EXCEPTIONS)
                        ? exceptionsService.report(exceptionStore).groups()
                        : List.<ExceptionGroupDto>of(),
                securityEvents(securityAvailable),
                securityAvailable,
                // No Quarkus cache-access capture seam exists yet (see LiveActivityAssembler's class
                // Javadoc); cacheHitRatioPercent stays null, exactly as before this parameter was added.
                null,
                false,
                scheduledTaskRunStore.runs(),
                null,
                limit,
                kafkaAvailable ? kafkaRecorder.recent() : List.of(),
                kafkaAvailable,
                // Quarkus has no JMS capture seam (jakarta.jms is a Spring-adapter concern), so the shared
                // assembler's JMS slot is explicitly empty rather than approximated.
                null,
                false,
                rabbitAvailable ? rabbitRecorder.recent() : List.of(),
                rabbitAvailable,
                emailAvailable ? emailReport.messages() : List.<EmailMessageDto>of(),
                emailAvailable,
                // Quarkus REST Client Reactive capture via QuarkusRestClientTraceListener SPI.
                restClientAvailable
                        ? restClientTraceRecorder
                                .report(exposure.maskSecrets(), exposure.valueExposure())
                                .entries()
                        : List.<RestClientTraceEntryDto>of(),
                restClientAvailable,
                faultToleranceAvailable
                        ? faultToleranceRecorder.recent()
                        : List.<FaultToleranceEventRecorder.CapturedEvent>of(),
                faultToleranceAvailable);

        // A REQUEST entry is profileable when its exchange carries a trace id or BootUI's request id, the exact
        // signals the reduced profile correlates on. The engine owns the rule, shared with the other reactive stack.
        return LiveActivityAssembler.withExactProfiles(report, requests == null ? List.of() : requests.exchanges());
    }

    /**
     * The trace-id-only per-request profile drill-down — see the class Javadoc for why the serving-thread
     * and time-window tiers are reported unavailable. Gathers the same masked, self-filtered signal sources
     * {@link #activity} does, plus REST Client Reactive calls, then delegates all correlation and
     * honest-degrade shaping to the shared engine assembler. Cache accesses have no capture seam on
     * Quarkus, so that section reports itself unavailable.
     */
    /**
     * One request as the runtime journal recorded it ({@code docs/PLAN-v2.md} §5.3, §5.11): its timeline, GC pauses,
     * measured resources, route comparison, and touched resources.
     */
    @GET
    @Path("/request/{id}/journal")
    @Produces(MediaType.APPLICATION_JSON)
    public RequestJournalProfileDto requestJournal(@PathParam("id") String id) {
        return new RequestJournalProfiles(
                        journal != null && journal.isResolvable() ? journal.get() : null,
                        journalAggregates != null && journalAggregates.isResolvable() ? journalAggregates.get() : null,
                        buffer.slowThresholdMillis(),
                        SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD,
                        panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel),
                        exposure)
                .maxHandoff(maxHandoff)
                .profile(id);
    }

    public RequestProfileSelectionDto agentProfile(String id) {
        return RequestProfileSelection.select(id, this::requestJournal, this::request);
    }

    @GET
    @Path("/request/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public RequestProfileDto request(@PathParam("id") String id) {
        List<HttpExchangeDto> requests = requestsReport().exchanges();
        boolean found = requests.stream().anyMatch(exchange -> ExecutionProfileAssembler.identifies(exchange, id));
        ProfileEvidence evidence = found
                ? new ProfileEvidence(
                        requests,
                        sqlSource(),
                        exceptionSource(),
                        securitySource(),
                        restCallSource(),
                        Source.unavailable(CACHE_UNAVAILABLE),
                        traceId -> traceId == null || traceId.isBlank()
                                ? null
                                : tracesService.detail(traceId).orElse(null))
                : new ProfileEvidence(requests, null, null, null, null, null, null);
        return profileAssembler.requestProfile(
                id, evidence, ProfileCapabilities.traceIdOnly().withPropagation(propagationUnavailableReason()));
    }

    private Source<SqlTraceEntryDto> sqlSource() {
        if (!panelAvailability.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return Source.panelDisabled("SQL Trace");
        }
        SqlSnapshot sql = sqlSnapshot();
        SqlTraceRecorder recorder = sqlRecorder.isResolvable() ? sqlRecorder.get() : null;
        return sql.available()
                ? Source.of(sql.entries(), recorder == null ? null : recorder.executionCaptureLimitation())
                : Source.unavailable(sql.unavailableWarning());
    }

    private Source<ExceptionDetailDto> exceptionSource() {
        return panelAvailability.isPanelEnabled(BootUiPanels.EXCEPTIONS)
                ? Source.of(allExceptionDetails())
                : Source.panelDisabled("Exceptions");
    }

    private Source<SecurityLogEventDto> securitySource() {
        if (!panelAvailability.isPanelEnabled(BootUiPanels.SECURITY_LOGS)) {
            return Source.panelDisabled("Security Logs");
        }
        return panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS)
                ? Source.of(securityEvents(true))
                : Source.notCapturing("Security Logs");
    }

    private Source<RestClientTraceEntryDto> restCallSource() {
        if (!panelAvailability.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)) {
            return Source.panelDisabled("REST Client");
        }
        return restClientActivityAvailable()
                ? Source.of(restClientTraceRecorder
                        .report(exposure.maskSecrets(), exposure.valueExposure())
                        .entries())
                : Source.notCapturing("REST Client");
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@Context Sse sse) {
        return SseStreams.updates(
                sse,
                openStreams,
                MAX_CONCURRENT_STREAMS,
                combined(
                        combined(
                                combined(
                                        combined(
                                                combined(
                                                        combined(
                                                                combined(
                                                                        buffer::subscribe,
                                                                        scheduledTaskRunStore::subscribe),
                                                                kafkaRecorder::subscribe),
                                                        rabbitRecorder::subscribe),
                                                emailChangeSource()),
                                        restClientChangeSource()),
                                sqlChangeSource()),
                        combined(
                                combined(exceptionStore::subscribe, faultToleranceChangeSource()),
                                journalChangeSource())));
    }

    /**
     * Ticks the merged stream whenever the runtime journal records a batch, so a log event or any other source only
     * the journal records refreshes the panel too ({@code docs/PLAN-v2.md} §5.3).
     */
    private SseStreams.ChangeSource journalChangeSource() {
        return onChange ->
                journal != null && journal.isResolvable() ? journal.get().subscribe(onChange) : () -> {};
    }

    /**
     * Ticks the merged stream whenever a fault tolerance outcome is captured, so a breaker transition or a retry
     * on a background path refreshes Live Activity instead of leaving it stale until an unrelated signal
     * arrives. The recorder itself is inert when the panel is unavailable or capture is disabled, so no
     * further gate is needed here.
     */
    private SseStreams.ChangeSource faultToleranceChangeSource() {
        return faultToleranceRecorder::subscribe;
    }

    /**
     * Combines two {@link SseStreams.ChangeSource}s into one that notifies {@code onChange} when either
     * fires, so the merged Live Activity stream ticks on a new HTTP exchange, a new captured
     * {@code @Scheduled} execution, a new captured Kafka or RabbitMQ message, a new captured email, a REST
     * Client call, a new traced SQL statement, a newly captured exception, <em>or</em> a newly captured
     * fault tolerance outcome (nested at the call site to fan in all of them) — mirroring the Spring adapter,
     * whose single {@code BootUiChangeStream} already fans in every signal source to the same effect.
     *
     * <p>SQL and exception capture were previously the two sources that fed {@link #activity} but never
     * ticked this stream, so a purely database-driven or purely failing workload left the panel — and the
     * Live Flow map that refreshes from the same stream — silently stale until an unrelated signal arrived.
     * Both now subscribe like every other source.</p>
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

    /**
     * Whether a panel's evidence may be shown: the application can serve it and the user enabled it, the same rule as
     * the journal's source-panel predicate ({@code docs/PLAN-v2.md} §8).
     */
    private boolean panelVisible(String panel) {
        return panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel);
    }

    private HttpExchangesReport requestsReport() {
        if (!panelVisible(BootUiPanels.HTTP_EXCHANGES)) {
            return HttpExchangesReport.unavailable("HTTP Exchanges panel is disabled");
        }
        return exchanges.report(
                buffer.snapshot(),
                // The capture filter never records BootUI's own requests, judged below the root path.
                HttpExchangesService.BootUiSelfPath.EXCLUDED_AT_CAPTURE,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                declaredRoutes.get(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private EmailsReport emailReport() {
        if (!panelVisible(BootUiPanels.EMAIL) || !emailCaptureService.isResolvable()) {
            return null;
        }
        return emailCaptureService.get().list();
    }

    private SseStreams.ChangeSource emailChangeSource() {
        return onChange -> {
            if (!emailCaptureService.isResolvable()) {
                return () -> {};
            }
            return emailCaptureService.get().subscribe(onChange);
        };
    }

    private SseStreams.ChangeSource restClientChangeSource() {
        return onChange -> restClientTraceRecorder.subscribe(() -> {
            if (restClientActivityAvailable()) {
                onChange.run();
            }
        });
    }

    /**
     * Ticks the merged stream whenever a new JDBC statement is traced. The recorder is an optional bean (no
     * datasource means no recorder), so an unresolvable instance yields an inert source rather than failing
     * the stream.
     */
    private SseStreams.ChangeSource sqlChangeSource() {
        return onChange -> {
            if (!sqlRecorder.isResolvable()) {
                return () -> {};
            }
            return sqlRecorder.get().subscribe(onChange);
        };
    }

    private boolean restClientActivityAvailable() {
        return panelAvailability.isPanelAvailable(BootUiPanels.REST_CLIENT_TRACE)
                && panelAvailability.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)
                && restClientTraceRecorder.isEnabled()
                && restClientTraceRecorder.hasInstrumentedClient();
    }

    private SqlSnapshot sqlSnapshot() {
        if (!panelAvailability.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return new SqlSnapshot(List.of(), false, "SQL Trace panel is disabled");
        }
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
        return new SqlSnapshot(rec.entries(exposeParameters), true, null);
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

    /** SQL trace snapshot for one request cycle: entries plus whether the source is present and feeding. */
    private record SqlSnapshot(List<SqlTraceEntryDto> entries, boolean available, String unavailableWarning) {}
}
