package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiEngineConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearResult;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcesDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.github.jdubois.bootui.engine.journal.JournalActivityCapture;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.ServletRequestHandledEvent;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-only Live Activity endpoints: a merged, reverse-chronological stream of recent application
 * activity plus a Symfony-style per-request profile. Both reuse BootUI's existing signal controllers
 * so masking, self-filtering and buffer bounds are inherited; this controller adds no instrumentation.
 *
 * <p>Because the merged feed is genuinely event-driven, the panel refreshes over Server-Sent Events
 * instead of fixed-interval polling: {@link #stream()} pushes a tiny coalesced {@code update} tick
 * whenever any underlying source changes, and the browser re-fetches {@link #activity} so all
 * masking, filtering and bounds still apply. SQL trace, REST client trace, exceptions, cache
 * accesses, scheduled-task runs, Kafka/JMS/RabbitMQ messages, and captured emails are wired in as
 * signals through their in-process subscribe hooks; security and HTTP requests are wired through
 * Spring application events — a single {@link BootUiChangeStream} coalesces a burst into one push.
 *
 * <p>This controller also always owns the capture side: whenever the injected {@link
 * #persistenceSettings} has persistence enabled (from startup configuration, or later via the "Use the
 * existing datasource" switch — see {@link #useExistingDatasource}), the runtime journal's subscriber renders and
 * appends each recorded batch (see {@code JournalActivityCapture}) into
 * the shared {@link SwitchableActivityStore} bean, and {@link #activity} then serves entries and
 * pagination from that store — which itself merges its in-memory hot cache with the durable backend —
 * instead of from a fresh live re-merge. The store bean always exists (even with persistence disabled,
 * as a bare in-memory store), so {@link #activity} branches on the store's own live {@code
 * persistent()} state rather than the static startup settings, correctly reflecting a runtime switch.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/activity")
public class LiveActivityController implements InitializingBean {
    private static final org.apache.commons.logging.Log log =
            org.apache.commons.logging.LogFactory.getLog(LiveActivityController.class);

    private final LiveActivityService service;
    private final LiveActivityCorrelator correlator;
    private final SecurityEventCorrelationRegistry securityCorrelations;
    private final BootUiChangeStream changeStream;
    private final List<Runnable> unsubscribers = Collections.synchronizedList(new ArrayList<>());
    private final String selfPath;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ReservedActivityEntries reservedEntries;
    private final BootUiProperties properties;
    private final ActivityFeedSource feedSource;
    private volatile JournalActivityReports journalReports;
    private volatile RuntimeJournal captureJournal;
    private volatile Supplier<RouteTemplateResolver> captureRoutes;
    private volatile ActivityPersistenceSettings deferredCapture;
    private volatile RequestJournalProfiles requestJournalProfiles;

    public LiveActivityController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<RestClientTraceController> restClientTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<HealthController> health,
            ObjectProvider<EmailController> email,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<RestClientTraceRecorder> restClientTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            ObjectProvider<ScheduledTaskRunStore> scheduledTaskRuns,
            ObjectProvider<KafkaActivityRecorder> kafkaActivityRecorder,
            ObjectProvider<JmsActivityRecorder> jmsActivityRecorder,
            ObjectProvider<RabbitActivityRecorder> rabbitActivityRecorder,
            ObjectProvider<FaultToleranceEventRecorder> faultToleranceEventRecorder,
            ObjectProvider<EmailCaptureService> emailCaptureService,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiProperties properties) {
        this.service = new LiveActivityService(
                httpExchanges,
                sqlTrace,
                restClientTrace,
                exceptions,
                securityLogs,
                health,
                email,
                requestCorrelations,
                securityCorrelations,
                cacheActivity,
                scheduledTaskRuns,
                kafkaActivityRecorder,
                jmsActivityRecorder,
                rabbitActivityRecorder,
                faultToleranceEventRecorder,
                properties);
        this.correlator = new LiveActivityCorrelator(
                httpExchanges,
                sqlTrace,
                restClientTrace,
                exceptions,
                securityLogs,
                traces,
                cacheActivity,
                requestCorrelations,
                securityCorrelations,
                properties);
        this.securityCorrelations = securityCorrelations.getIfAvailable();
        this.changeStream = new BootUiChangeStream("activity");
        this.selfPath = properties.getPath();
        this.properties = properties;
        this.feedSource = properties.getActivity().feedSource();
        this.journalReports = journalReports(null, null);
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder != null) {
            unsubscribers.add(recorder.subscribe(changeStream::signal));
        }
        RestClientTraceRecorder restRecorder = restClientTraceRecorder.getIfAvailable();
        if (restRecorder != null) {
            unsubscribers.add(restRecorder.subscribe(changeStream::signal));
        }
        ExceptionStore store = exceptionStore.getIfAvailable();
        if (store != null) {
            unsubscribers.add(store.subscribe(changeStream::signal));
        }
        CacheActivityRecorder cache = cacheActivity.getIfAvailable();
        if (cache != null) {
            unsubscribers.add(cache.subscribe(changeStream::signal));
        }
        ScheduledTaskRunStore scheduledStore = scheduledTaskRuns.getIfAvailable();
        if (scheduledStore != null) {
            unsubscribers.add(scheduledStore.subscribe(changeStream::signal));
        }
        KafkaActivityRecorder kafkaRecorder = kafkaActivityRecorder.getIfAvailable();
        if (kafkaRecorder != null) {
            unsubscribers.add(kafkaRecorder.subscribe(changeStream::signal));
        }
        JmsActivityRecorder jmsRecorder = jmsActivityRecorder.getIfAvailable();
        if (jmsRecorder != null) {
            unsubscribers.add(jmsRecorder.subscribe(changeStream::signal));
        }
        RabbitActivityRecorder rabbitRecorder = rabbitActivityRecorder.getIfAvailable();
        if (rabbitRecorder != null) {
            unsubscribers.add(rabbitRecorder.subscribe(changeStream::signal));
        }
        FaultToleranceEventRecorder faultToleranceRecorder = faultToleranceEventRecorder.getIfAvailable();
        if (faultToleranceRecorder != null) {
            unsubscribers.add(faultToleranceRecorder.subscribe(changeStream::signal));
        }
        EmailCaptureService emailCapture = emailCaptureService.getIfAvailable();
        if (emailCapture != null) {
            unsubscribers.add(emailCapture.subscribe(changeStream::signal));
        }
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.dataSourceProvider = dataSourceProvider;
        // The same bootui.activity.request-slow-threshold-ms the exchange repository classifies with. The repository
        // falls back to 0 only when time-taken is not recorded, and then no exchange carries a duration to be slow.
        this.reservedEntries =
                new ReservedActivityEntries(properties.getActivity().getRequestSlowThresholdMs());
        if (persistenceSettings.enabled()) {
            startPersistence(persistenceSettings);
        }
    }

    /**
     * Starts writing Live Activity's durable history from the same source the feed reads ({@code docs/PLAN-v2.md}
     * §5.3): the runtime journal's subscriber, whatever source the feed reads; 2.0.0 removed the poller of the panel
     * buffers. The journal is installed after this controller is built, so a capture asked for before then
     * starts when it is installed; with no journal, or a disabled one, persistence logs a warning and writes nothing.
     *
     * @return the running capture, or {@code null} when it waits for the journal
     */
    ActivityCapture startPersistence(ActivityPersistenceSettings settings) {
        RuntimeJournal current = captureJournal;
        if (current == null) {
            deferredCapture = settings;
            return null;
        }
        if (current.settings().enabled()) {
            JournalActivityCapture capture = JournalActivityCapture.start(
                    activityStore,
                    settings,
                    reservedEntries,
                    current,
                    new JournalActivityFeed(
                            properties.getActivity().getRequestSlowThresholdMs(),
                            properties.getActivity().getNPlusOneThreshold(),
                            captureRoutes),
                    properties::isPanelEnabled);
            unsubscribers.add(capture::close);
            return capture;
        }
        log.warn("Live Activity persistence is enabled, but the runtime journal is disabled"
                + " (bootui.runtime-journal.enabled=false), so no durable history is written: the journal is its only"
                + " source in 2.0.");
        return null;
    }

    /**
     * Reports a capture the journal never arrived for, once every setter has run. An initialization
     * callback rather than a context event, so this lazy controller is still created only when first used.
     */
    @Override
    public void afterPropertiesSet() {
        ActivityPersistenceSettings settings = deferredCapture;
        if (settings != null) {
            deferredCapture = null;
            log.warn("Live Activity persistence is enabled, but no runtime journal was installed, so no durable"
                    + " history is written: the journal is its only source in 2.0.");
        }
    }

    /**
     * Completes any open SSE streams and detaches the merged source listeners when the context starts
     * closing.
     *
     * <p>Runs on {@link ContextClosedEvent} rather than {@code @PreDestroy}: the event is published
     * before the web server's graceful-shutdown lifecycle waits for in-flight requests, whereas
     * {@code @PreDestroy} runs during later bean destruction. An {@code SseEmitter(0L)} never completes
     * on its own, so cleaning up at destroy time would let graceful shutdown block until its timeout on
     * every stop. Doing it here also keeps a Spring Boot DevTools restart from leaking the
     * {@code bootui-activity-stream} daemon thread (and the discarded context's class loader behind it).
     * The journal capture (when persistence is enabled) is stopped the same way, for the same reason;
     * the shared {@link SwitchableActivityStore} bean itself is closed separately by Spring's own
     * inferred destroy-method lifecycle since it holds no open request/connection that shutdown must
     * not block on.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        unsubscribers.forEach(Runnable::run);
        unsubscribers.clear();
        changeStream.close();
    }

    /** The feed from the configured source, without the journal-only filters; for callers such as the MCP tools. */
    public LiveActivityReport activity(
            String type, String severity, long since, int limit, String q, Long until, String cursor, int pageSize) {
        return activity(type, severity, since, limit, q, until, cursor, pageSize, null, null, null, null, false);
    }

    @GetMapping
    public LiveActivityReport activity(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "since", required = false, defaultValue = "0") long since,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "until", required = false) Long until,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false, defaultValue = "0") int pageSize,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "route", required = false) String route,
            @RequestParam(name = "run", required = false) String run,
            @RequestParam(name = "requestId", required = false) String requestId,
            @RequestParam(name = "noRequest", required = false, defaultValue = "false") boolean noRequest) {
        JournalActivityFeed.Filter filter =
                new JournalActivityFeed.Filter(type, severity, since, route, run, requestId, noRequest);
        LiveActivityReport live = live(source, filter, limit);
        ActivityPersistenceOptionDto persistenceOption = new ActivityPersistenceOptionDto(
                activityStore.persistent(),
                BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider) != null,
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
        // and the dashboard can page back through history beyond what fits in memory. KPIs/type counts/
        // sources/warnings stay computed from the current live merge above — that strip is an "at a
        // glance, right now" summary, not scoped to whichever historical page happens to be browsed.
        ActivityQuery query = new ActivityQuery(
                persistenceSettings.instanceId(), type, severity, q, since > 0 ? since : null, until, cursor, pageSize);
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

    private volatile RuntimeJournalService runtimeJournal = new RuntimeJournalService(null, null);

    /** Installs the Java Agent service, which says whether the request profile's {@code PROPAGATED} tier applies. */
    @Autowired(required = false)
    public void setJavaAgent(ObjectProvider<JavaAgentService> javaAgent) {
        correlator.setJavaAgent(javaAgent);
    }

    /** Installs the runtime journal whose status block and <b>Clear recording</b> this panel serves. */
    @Autowired(required = false)
    public void setRuntimeJournal(RuntimeJournal journal, JournalAggregates aggregates) {
        this.runtimeJournal = new RuntimeJournalService(journal, aggregates);
        this.captureJournal = journal;
        this.captureRoutes = aggregates == null ? null : aggregates.declaredRoutes();
        ActivityPersistenceSettings deferred = deferredCapture;
        if (deferred != null && journal != null) {
            deferredCapture = null;
            startPersistence(deferred);
        }
        this.journalReports = journalReports(journal, aggregates);
        this.requestJournalProfiles = new RequestJournalProfiles(
                        journal,
                        aggregates,
                        properties.getActivity().getRequestSlowThresholdMs(),
                        properties.getActivity().getNPlusOneThreshold(),
                        properties::isPanelEnabled)
                .maxHandoff(properties.getAgent().getExecutors().getMaxHandoff());
        if (journal != null) {
            // Ticks the stream for every source the journal records, transactions and log events included.
            unsubscribers.add(journal.subscribe(changeStream::signal));
        }
    }

    private JournalActivityReports journalReports(RuntimeJournal journal, JournalAggregates aggregates) {
        return new JournalActivityReports(
                journal,
                properties.getActivity().getRequestSlowThresholdMs(),
                properties.getActivity().getNPlusOneThreshold(),
                aggregates == null ? null : aggregates.declaredRoutes(),
                properties::isPanelEnabled);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        // With the journal disabled, the panel buffers serve the feed rather than leaving it empty.
        if (resolved == ActivityFeedSource.JOURNAL && journalReports.recording()) {
            int max = properties.getActivity().getMaxEntries();
            return journalReports.report(
                    filter, limit <= 0 ? max : limit, service.currentHealthStatus(), service.journalRowDetails());
        }
        LiveActivityReport live = service.report(filter.type(), filter.severity(), filter.since(), limit);
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

    /** The runtime journal's status block ({@code docs/PLAN-v2.md} §5.2). */
    @GetMapping("/journal")
    public RuntimeJournalStatusDto journal() {
        return runtimeJournal.status();
    }

    /** The run's resource track and CPU ledger ({@code docs/PLAN-v2.md} §5.11). */
    @GetMapping("/resources")
    public RuntimeResourcesDto resources() {
        return runtimeJournal.resources();
    }

    /** <b>Clear recording</b>: drops the run's recorded events and aggregates, when confirmed. */
    @PostMapping("/journal/clear")
    public ResponseEntity<RuntimeJournalClearResult> clearJournal(
            @RequestBody(required = false) RuntimeJournalClearRequest request) {
        RuntimeJournalService.Response response = runtimeJournal.clear(request);
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    /**
     * Hot-switches Live Activity from in-memory to durable JDBC persistence, reusing the host
     * application's own {@code DataSource} — no restart required. Gated by explicit confirmation (this
     * creates a database table and starts writing to it) and by BootUI's global/per-panel read-only
     * filter, like every other mutating panel action. Idempotent: calling this when persistence is
     * already active is a no-op that reports success rather than an error. On success, starts this
     * controller's own journal capture against the newly durable store, exactly as the constructor would
     * have done had persistence been enabled from startup.
     */
    @PostMapping("/use-existing-datasource")
    public ResponseEntity<ActivitySwitchResult> useExistingDatasource(
            @RequestBody(required = false) ActivitySwitchRequest request) {
        DataSource dataSource = BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider);
        ActivitySwitchResponse response = new ActivitySwitchService()
                .useExistingDataSource(activityStore, persistenceSettings, dataSource, request);
        if (response.newSettings() != null) {
            startPersistence(response.newSettings());
        }
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    /**
     * One request as the runtime journal recorded it ({@code docs/PLAN-v2.md} §5.3, §5.11): its timeline, GC pauses,
     * measured resources, route comparison, and touched resources.
     */
    @GetMapping("/request/{id}/journal")
    public RequestJournalProfileDto requestJournal(@PathVariable("id") String id) {
        RequestJournalProfiles profiles = requestJournalProfiles;
        return profiles == null
                ? new RequestJournalProfiles(
                                null,
                                null,
                                properties.getActivity().getRequestSlowThresholdMs(),
                                properties.getActivity().getNPlusOneThreshold(),
                                properties::isPanelEnabled)
                        .profile(id)
                : profiles.profile(id);
    }

    @GetMapping("/request/{id}")
    public RequestProfileDto request(@PathVariable("id") String id) {
        return correlator.profile(id);
    }

    /**
     * Streams a coalesced {@code update} notification whenever any merged source changes, so the
     * browser can refresh the feed live without polling. The push is a tiny tick; the browser
     * re-fetches {@link #activity} on each tick, preserving all filtering, bounds and masking.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }

    /**
     * Signals on each completed host request so new {@code REQUEST} rows appear live. BootUI's own
     * traffic (including the panel's re-fetches and this SSE connection) is excluded so the stream
     * cannot trigger itself in a refresh loop.
     */
    @EventListener
    public void onRequestHandled(ServletRequestHandledEvent event) {
        if (isHostRequest(event.getRequestUrl(), selfPath)) {
            changeStream.signal();
        }
    }

    /**
     * Signals whenever a security audit event is recorded so {@code SECURITY} rows appear live, and
     * captures the worker thread the event was published on. Spring Boot publishes this event
     * synchronously on the request's serving thread, so recording {@code (thread, type, timestamp,
     * principal)} here lets the profiler pin audit events to the exact request that produced them.
     */
    @EventListener
    public void onAuditEvent(AuditApplicationEvent event) {
        AuditEvent audit = event.getAuditEvent();
        if (securityCorrelations != null && audit != null && audit.getTimestamp() != null) {
            securityCorrelations.record(new SecurityEventCorrelationRegistry.SecurityEventCorrelation(
                    audit.getTimestamp().toEpochMilli(),
                    Thread.currentThread().getName(),
                    audit.getType(),
                    audit.getPrincipal()));
        }
        changeStream.signal();
    }

    /**
     * A request counts as host (non-BootUI) traffic worth signalling unless its URL targets BootUI's
     * own base path. The panel's own re-fetches and SSE connection always carry a {@code /bootui/...}
     * URL, so excluding that path is what breaks the self-refresh loop; a rare {@code null} URL is
     * treated as host traffic since an occasional extra tick is harmless.
     */
    static boolean isHostRequest(String requestUrl, String selfPath) {
        return requestUrl == null || !requestUrl.contains(selfPath);
    }
}
