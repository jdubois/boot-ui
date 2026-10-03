package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiEngineConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.javaagent.AgentPropagation;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceControllerSupport;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearResult;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcesDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.github.jdubois.bootui.engine.journal.JournalActivityCapture;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalRowDetails;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RequestProfileSelection;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities;
import io.github.jdubois.bootui.engine.web.ProfileEvidence;
import io.github.jdubois.bootui.engine.web.ProfileEvidence.Source;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code LiveActivityController}. Unlike the servlet controller (which
 * correlates signals to a request using thread-per-request heuristics — serving thread, time window —
 * that have no equivalent on Reactor Netty's event-loop model), this controller reuses the
 * framework-neutral {@link LiveActivityAssembler} the Quarkus adapter already validated for exactly this
 * constraint, and serves the per-request profile through the shared {@link ExecutionProfileAssembler} with
 * {@link ProfileCapabilities#traceIdOnly() trace-id-only capabilities}: correlation is driven purely by exact
 * identities, BootUI's request id and a shared distributed trace id (see {@code CorrelationContextProvider}), and a request
 * with neither simply renders flat/unprofileable rather than guessing.
 *
 * <p>All nine signal sources are read directly from the already-reactive, already-masked/self-filtered
 * beans this adapter wires for their own panels — {@link HttpExchangesController} (HTTP requests, shared
 * with the servlet adapter since it depends only on the stack-agnostic Actuator
 * {@code HttpExchangeRepository}), {@link SqlTraceRecorder} (SQL trace), {@link RestClientTraceRecorder}
 * (outbound REST/WebClient calls), {@link ExceptionStore} (exceptions),
 * {@code ReactiveSecurityLogsController} (security/audit events), {@link ScheduledTaskRunStore}
 * ({@code @Scheduled} executions), {@link CacheActivityRecorder} (cache hit/miss/eviction events),
 * {@link KafkaActivityRecorder}, {@link JmsActivityRecorder}, and {@link RabbitActivityRecorder}
 * (captured producer/consumer metadata), and {@link EmailController}
 * (captured outgoing emails, via the shared {@code EmailCaptureService}) — so this controller adds no new
 * capture instrumentation of its own, only the merge. Because those beans are reached directly (bypassing
 * the HTTP layer, and with it
 * {@code ReactivePanelAccessFilter}'s per-panel enablement check), every signal read here re-checks
 * {@code properties.isPanelEnabled(...)} itself first, exactly mirroring
 * {@code LiveActivityService}/{@code LiveActivityCorrelator}.
 *
 * <p>The optional JDBC persistence backend and the "Use the existing datasource" hot-switch are identical
 * to the servlet controller (same shared {@link SwitchableActivityStore}/{@link ActivityPersistenceSettings}
 * beans, same {@link ActivitySwitchService}); see {@code LiveActivityController}'s Javadoc for the full
 * rationale, which applies unchanged here.
 *
 * <p>The merged feed refreshes over Server-Sent Events, like every other reactive panel: since there is no
 * WebFlux equivalent of the servlet {@code ServletRequestHandledEvent} used to trigger a tick,
 * {@link ReactiveActivitySignalFilter} calls {@link #signalRequestHandled()} after every non-BootUI request
 * completes, and the SQL trace recorder / exception store / scheduled-task-store / cache recorder / Kafka
 * / JMS recorder / RabbitMQ recorder / email capture service subscriptions signal directly, same
 * as servlet.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/activity")
public class ReactiveLiveActivityController implements InitializingBean {
    private static final org.apache.commons.logging.Log log =
            org.apache.commons.logging.LogFactory.getLog(ReactiveLiveActivityController.class);

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceRecorder> sqlTraceRecorder;
    private final ObjectProvider<RestClientTraceRecorder> restClientTrace;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<ExceptionStore> exceptionStoreProvider;
    private final ObjectProvider<ScheduledTaskRunStore> scheduledTaskActivity;
    private final ObjectProvider<ReactiveSecurityLogsController> securityLogs;
    private final ObjectProvider<TracesController> traces;
    private final ObjectProvider<HealthController> health;
    private final ObjectProvider<EmailController> email;
    private final ObjectProvider<EmailCaptureService> emailCaptureService;
    private final ObjectProvider<CacheActivityRecorder> cacheActivity;
    private final ObjectProvider<KafkaActivityRecorder> kafkaActivity;
    private final ObjectProvider<JmsActivityRecorder> jmsActivity;
    private final ObjectProvider<FaultToleranceEventRecorder> faultToleranceEvents;
    private final ObjectProvider<RabbitActivityRecorder> rabbitActivity;
    private final BootUiProperties properties;
    private final ActivityFeedSource feedSource;
    private final BootUiExposure exposure;
    private final ExceptionsService exceptionsService;
    private final ReactiveBootUiChangeStream changeStream;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final LiveActivityAssembler assembler;
    private final ReservedActivityEntries reservedEntries;
    private final ExecutionProfileAssembler profileAssembler;
    private volatile ObjectProvider<JavaAgentService> javaAgent;
    private final List<Runnable> unsubscribers = Collections.synchronizedList(new ArrayList<>());

    public ReactiveLiveActivityController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<RestClientTraceRecorder> restClientTrace,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<ExceptionStore> exceptionStoreProvider,
            ObjectProvider<ScheduledTaskRunStore> scheduledTaskActivity,
            ObjectProvider<ReactiveSecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<HealthController> health,
            ObjectProvider<EmailController> email,
            ObjectProvider<EmailCaptureService> emailCaptureService,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            ObjectProvider<KafkaActivityRecorder> kafkaActivity,
            ObjectProvider<JmsActivityRecorder> jmsActivity,
            ObjectProvider<FaultToleranceEventRecorder> faultToleranceEvents,
            ObjectProvider<RabbitActivityRecorder> rabbitActivity,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            BootUiProperties properties,
            BootUiExposure exposure) {
        this.httpExchanges = httpExchanges;
        this.sqlTraceRecorder = sqlTraceRecorder;
        this.restClientTrace = restClientTrace;
        this.dataSourceProvider = dataSourceProvider;
        this.exceptionStoreProvider = exceptionStoreProvider;
        this.scheduledTaskActivity = scheduledTaskActivity;
        this.securityLogs = securityLogs;
        this.traces = traces;
        this.health = health;
        this.email = email;
        this.emailCaptureService = emailCaptureService;
        this.cacheActivity = cacheActivity;
        this.kafkaActivity = kafkaActivity;
        this.jmsActivity = jmsActivity;
        this.faultToleranceEvents = faultToleranceEvents;
        this.rabbitActivity = rabbitActivity;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.properties = properties;
        this.profileAssembler = new ExecutionProfileAssembler(
                properties.getActivity().getNPlusOneThreshold(),
                ExecutionProfileAssembler.DEFAULT_MAX_CHILDREN_PER_SECTION,
                properties.getAgent().getExecutors().getMaxHandoff().toMillis());
        this.feedSource = properties.getActivity().feedSource();
        this.exposure = exposure;
        this.assembler = new LiveActivityAssembler(properties.getActivity().getRequestSlowThresholdMs());
        // The threshold the exchange repository classifies with; see LiveActivityController for why this is exact.
        this.reservedEntries =
                new ReservedActivityEntries(properties.getActivity().getRequestSlowThresholdMs());
        this.exceptionsService = new ExceptionsService(exposure);
        this.changeStream = new ReactiveBootUiChangeStream("activity");
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder != null) {
            unsubscribers.add(recorder.subscribe(changeStream::signal));
        }
        RestClientTraceRecorder restClientTraceRecorder = restClientTrace.getIfAvailable();
        if (restClientTraceRecorder != null) {
            unsubscribers.add(restClientTraceRecorder.subscribe(changeStream::signal));
        }
        ExceptionStore store = exceptionStoreProvider.getIfAvailable();
        if (store != null) {
            unsubscribers.add(store.subscribe(changeStream::signal));
        }
        ScheduledTaskRunStore scheduledStore = scheduledTaskActivity.getIfAvailable();
        if (scheduledStore != null) {
            unsubscribers.add(scheduledStore.subscribe(changeStream::signal));
        }
        CacheActivityRecorder cacheRecorder = cacheActivity.getIfAvailable();
        if (cacheRecorder != null) {
            unsubscribers.add(cacheRecorder.subscribe(changeStream::signal));
        }
        KafkaActivityRecorder kafkaRecorder = kafkaActivity.getIfAvailable();
        if (kafkaRecorder != null) {
            unsubscribers.add(kafkaRecorder.subscribe(changeStream::signal));
        }
        JmsActivityRecorder jmsRecorder = jmsActivity.getIfAvailable();
        if (jmsRecorder != null) {
            unsubscribers.add(jmsRecorder.subscribe(changeStream::signal));
        }
        FaultToleranceEventRecorder faultToleranceRecorder = faultToleranceEvents.getIfAvailable();
        if (faultToleranceRecorder != null) {
            unsubscribers.add(faultToleranceRecorder.subscribe(changeStream::signal));
        }
        RabbitActivityRecorder rabbitRecorder = rabbitActivity.getIfAvailable();
        if (rabbitRecorder != null) {
            unsubscribers.add(rabbitRecorder.subscribe(changeStream::signal));
        }
        EmailCaptureService emailCapture = emailCaptureService.getIfAvailable();
        if (emailCapture != null) {
            unsubscribers.add(emailCapture.subscribe(changeStream::signal));
        }
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
     * closing. See {@code LiveActivityController#shutdown} for why this runs on
     * {@link ContextClosedEvent} rather than a destroy callback.
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
        LiveActivityReport live = live(
                source, new JournalActivityFeed.Filter(type, severity, since, route, run, requestId, noRequest), limit);
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
        // backend) serves entries and pagination — see LiveActivityController#activity for the full
        // rationale, unchanged here.
        ActivityQuery query = new ActivityQuery(
                persistenceSettings.instanceId(), type, severity, q, since > 0 ? since : null, until, cursor, pageSize);
        ActivityPage page = activityStore.query(query);
        return new LiveActivityReport(
                live.available(),
                // Stored rows were written under MASKED (or raw, by an older build); the live policy applies on read.
                page.entryDtos().stream()
                        .map(JournalTextExposure.of(exposure)::reapply)
                        .toList(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistenceOption);
    }

    private volatile RuntimeJournalService runtimeJournal = new RuntimeJournalService(null, null);
    private volatile JournalActivityReports journalReports;
    private volatile RuntimeJournal captureJournal;
    private volatile Supplier<RouteTemplateResolver> captureRoutes;
    private volatile ActivityPersistenceSettings deferredCapture;
    private volatile RequestJournalProfiles requestJournalProfiles;

    /** Installs the runtime journal whose status block and <b>Clear recording</b> this panel serves. */
    /** Installs the Java Agent service, which says whether the request profile's {@code PROPAGATED} tier applies. */
    @Autowired(required = false)
    public void setJavaAgent(ObjectProvider<JavaAgentService> javaAgent) {
        this.javaAgent = javaAgent;
    }

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
                        properties::isPanelEnabled,
                        exposure)
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
                properties::isPanelEnabled,
                exposure);
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
        JournalActivityReports reports = journalReports;
        // With the journal disabled or absent, the panel buffers serve the feed rather than leaving it empty.
        if (resolved == ActivityFeedSource.JOURNAL && reports != null && reports.recording()) {
            return reports.report(filter, limit, currentHealthStatus(), journalRowDetails());
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
     * Hot-switches Live Activity from in-memory to durable JDBC persistence. See
     * {@code LiveActivityController#useExistingDatasource} for the full rationale (confirmation gating,
     * idempotency, capture poller startup on success), which applies unchanged here.
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
     * The trace-id-only per-request profile drill-down, served by the shared
     * {@link ExecutionProfileAssembler}. The serving-thread and time-window tiers are reported unavailable:
     * no request served on the event loop has a stable owning thread to correlate on. Every source is read
     * through the same masked, self-filtered bean its own panel uses, and only when that panel is enabled.
     */
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
                                properties::isPanelEnabled,
                                exposure)
                        .profile(id)
                : profiles.profile(id);
    }

    public RequestProfileSelectionDto agentProfile(String id) {
        return RequestProfileSelection.select(id, this::requestJournal, this::request);
    }

    @GetMapping("/request/{id}")
    public RequestProfileDto request(@PathVariable("id") String id) {
        List<HttpExchangeDto> requests = requestsReport().exchanges();
        boolean found = requests.stream().anyMatch(exchange -> ExecutionProfileAssembler.identifies(exchange, id));
        ProfileEvidence evidence = found
                ? new ProfileEvidence(
                        requests,
                        sqlSource(),
                        exceptionSource(),
                        securitySource(),
                        restCallSource(),
                        cacheSource(),
                        this::correlateTrace)
                : new ProfileEvidence(requests, null, null, null, null, null, null);
        return profileAssembler.requestProfile(
                id,
                evidence,
                ProfileCapabilities.traceIdOnly().withPropagation(AgentPropagation.unavailableReason(javaAgent)));
    }

    private Source<SqlTraceEntryDto> sqlSource() {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return Source.panelDisabled("SQL Trace");
        }
        SqlSnapshot sql = sqlSnapshot();
        return sql.available() ? Source.of(sql.entries()) : Source.unavailable(sql.unavailableWarning());
    }

    private Source<ExceptionDetailDto> exceptionSource() {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return Source.panelDisabled("Exceptions");
        }
        return exceptionStoreProvider.getIfAvailable() == null
                ? Source.notCapturing("Exceptions")
                : Source.of(allExceptionDetails());
    }

    private Source<SecurityLogEventDto> securitySource() {
        if (!properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS)) {
            return Source.panelDisabled("Security Logs");
        }
        ReactiveSecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return Source.notCapturing("Security Logs");
        }
        SecurityLogsReport report = controller.logs(null, null, null, null, null);
        return report.auditEventsPresent()
                ? Source.of(report.events())
                : Source.unavailable(report.unavailableReason());
    }

    /** Read through the REST Client panel's own report, so availability and masking match that panel. */
    private Source<RestClientTraceEntryDto> restCallSource() {
        if (!properties.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)) {
            return Source.panelDisabled("REST Client");
        }
        RestClientTraceReport report = RestClientTraceControllerSupport.trace(restClientTrace, exposure);
        return report.available() ? Source.of(report.entries()) : Source.unavailable(report.unavailableReason());
    }

    private Source<CacheActivityEvent> cacheSource() {
        if (!properties.isPanelEnabled(BootUiPanels.CACHE)) {
            return Source.panelDisabled("Cache");
        }
        return Source.cacheAccesses(cacheActivity.getIfAvailable());
    }

    /**
     * Streams a coalesced {@code update} notification whenever any merged source changes, so the
     * browser can refresh the feed live without polling, exactly like the servlet {@code /stream}.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }

    /**
     * Signals that a non-BootUI request completed, so a new {@code REQUEST} row appears live. Called by
     * {@link ReactiveActivitySignalFilter}, which is the WebFlux replacement for the servlet controller's
     * {@code @EventListener ServletRequestHandledEvent} — there is no reactive equivalent of that event,
     * since WebFlux requests aren't dispatched through the servlet framework machinery that publishes it.
     */
    void signalRequestHandled() {
        changeStream.signal();
    }

    /**
     * The merged, reverse-chronological Live Activity feed — the entire {@link #activity} body before
     * persistence-aware pagination, extracted so the capture poller (started in the constructor, or by
     * {@link #useExistingDatasource}) can reuse it as its feed supplier without duplicating the
     * signal-gathering/masking/profileable-stamping logic. See {@code LiveActivityResource#mergedReport}
     * (the Quarkus analogue) for why this reuse matters: the poller must see the exact same self-filtered,
     * masked view the panel itself renders.
     */
    LiveActivityReport mergedReport(int limit) {
        HttpExchangesReport requests = requestsReport();
        SqlSnapshot sql = sqlSnapshot();
        boolean securityAvailable = properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS);
        List<RestClientTraceEntryDto> restEntries = restClientTraceEntries();
        boolean restAvailable = restEntries != null;
        List<ScheduledTaskRunStore.Run> scheduledRuns = scheduledTaskRuns();
        String healthStatus = currentHealthStatus();
        List<CacheActivityEvent> cacheEvents = cacheEvents();
        boolean cacheAvailable = cacheEvents != null;
        List<CapturedMessage> kafkaMessages = kafkaMessages();
        boolean kafkaAvailable = kafkaMessages != null;
        List<JmsActivityRecorder.CapturedMessage> jmsMessages = jmsMessages();
        boolean jmsAvailable = jmsMessages != null;
        List<RabbitActivityRecorder.CapturedMessage> rabbitMessages = rabbitMessages();
        boolean rabbitAvailable = rabbitMessages != null;
        EmailsReport emailReport = emailReport();
        boolean emailAvailable = emailReport != null && emailReport.available();
        List<FaultToleranceEventRecorder.CapturedEvent> faultToleranceCaptured = faultToleranceCaptured();
        boolean faultToleranceAvailable = faultToleranceCaptured != null;

        LiveActivityReport report = assembler.report(
                requests,
                sql.entries(),
                sql.available(),
                sql.unavailableWarning(),
                exceptionGroups(),
                securityEvents(securityAvailable),
                securityAvailable,
                cacheEvents,
                cacheAvailable,
                scheduledRuns,
                healthStatus,
                limit,
                kafkaMessages,
                kafkaAvailable,
                jmsMessages,
                jmsAvailable,
                rabbitMessages,
                rabbitAvailable,
                emailAvailable ? emailReport.messages() : List.<EmailMessageDto>of(),
                emailAvailable,
                restEntries,
                restAvailable,
                faultToleranceCaptured,
                faultToleranceAvailable);

        // A REQUEST entry is profileable when its exchange carries a trace id or BootUI's request id, the exact
        // signals the reduced profile correlates on. The engine owns the rule, shared with the other reactive stack.
        return LiveActivityAssembler.withExactProfiles(report, requests == null ? List.of() : requests.exchanges());
    }

    private HttpExchangesReport requestsReport() {
        if (!properties.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)) {
            return HttpExchangesReport.unavailable("HTTP Exchanges panel is disabled");
        }
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return HttpExchangesReport.unavailable("HTTP exchange repository not available");
        }
        return controller.exchanges(null, null, null, null, null);
    }

    /**
     * The masked detail the panel buffers still hold for the feed rendered from the runtime journal
     * ({@code docs/PLAN-v2.md} §5.3, D27): principals, exception messages, and email subjects.
     */
    private JournalRowDetails journalRowDetails() {
        HttpExchangesReport requests = requestsReport();
        EmailsReport emails = emailReport();
        return JournalRowDetails.of(
                requests == null ? null : requests.exchanges(),
                exceptionGroups(),
                emails == null || !emails.available() ? null : emails.messages(),
                securityEvents(properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS)));
    }

    private EmailsReport emailReport() {
        if (!properties.isPanelEnabled(BootUiPanels.EMAIL)) {
            return null;
        }
        EmailController controller = email.getIfAvailable();
        return controller == null ? null : controller.list();
    }

    private SqlSnapshot sqlSnapshot() {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return new SqlSnapshot(List.of(), false, null);
        }
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder == null || !recorder.isEnabled() || !recorder.hasWrappedDataSource()) {
            // Mirror the Quarkus resource's two-case reason: a present-but-disabled recorder is not the
            // same as an absent datasource, so don't tell the user to configure a datasource they already
            // have.
            String warning = (recorder != null && !recorder.isEnabled())
                    ? "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile)."
                    : "SQL trace is unavailable until a JDBC datasource is configured.";
            return new SqlSnapshot(List.of(), false, warning);
        }
        boolean exposeParameters =
                recorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return new SqlSnapshot(recorder.report(exposeParameters).entries(), true, null);
    }

    private List<ExceptionGroupDto> exceptionGroups() {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return List.of();
        }
        ExceptionStore store = exceptionStoreProvider.getIfAvailable();
        return store == null ? List.of() : exceptionsService.report(store).groups();
    }

    /**
     * Captured {@code @Scheduled} task executions, or {@code null} when the Scheduled Tasks panel is
     * disabled or the recorder bean is absent on this application.
     */
    private List<ScheduledTaskRunStore.Run> scheduledTaskRuns() {
        if (!properties.isPanelEnabled(BootUiPanels.SCHEDULED)) {
            return null;
        }
        ScheduledTaskRunStore store = scheduledTaskActivity.getIfAvailable();
        return store == null ? null : store.runs();
    }

    private List<RestClientTraceEntryDto> restClientTraceEntries() {
        if (!properties.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)) {
            return null;
        }
        RestClientTraceRecorder recorder = restClientTrace.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure()).entries();
    }

    private List<SecurityLogEventDto> securityEvents(boolean securityAvailable) {
        if (!securityAvailable) {
            return List.of();
        }
        ReactiveSecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        SecurityLogsReport report = controller.logs(null, null, null, null, null);
        return report.auditEventsPresent() ? report.events() : List.of();
    }

    /**
     * Recent cache accesses feeding the assembler's {@code CACHE} entries / {@code cacheHitRatioPercent}
     * KPI, or {@code null} when the source isn't feeding (Cache panel disabled, capture disabled via
     * {@code bootui.cache.activity-capture-enabled}, or no {@code CacheManager} bean present) — same
     * present-vs-absent distinction {@link #sqlSnapshot} and {@link #securityEvents} make, so the assembler
     * can tell "no cache access yet" from "no cache source at all".
     */
    private List<CacheActivityEvent> cacheEvents() {
        if (!properties.isPanelEnabled(BootUiPanels.CACHE)) {
            return null;
        }
        CacheActivityRecorder recorder = cacheActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.recentEvents();
    }

    /**
     * Recent Kafka messages feeding the assembler's {@code MESSAGING} entries, or {@code null} when the
     * source is not feeding (dedicated Kafka panel disabled, Kafka capture disabled via
     * {@code bootui.kafka.enabled}, or no recorder bean present) — same present-vs-absent distinction
     * {@link #sqlSnapshot()} and {@link #securityEvents(boolean)} make, so the assembler can tell "no
     * Kafka message yet" from "no Kafka source at all". Gated on the {@code KAFKA} panel, like
     * {@link #cacheEvents} gates on {@code CACHE} — its own domain panel, not Live Activity's.
     */
    private List<CapturedMessage> kafkaMessages() {
        if (!properties.isPanelEnabled(BootUiPanels.KAFKA)) {
            return null;
        }
        KafkaActivityRecorder recorder = kafkaActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.recent();
    }

    /** Recent JMS messages from the independent JMS bounded buffer. */
    /**
     * Recent fault tolerance outcomes feeding the assembler's {@code FAULT_TOLERANCE} entries, or {@code null} when
     * the source is not feeding (Fault Tolerance panel disabled, capture disabled via
     * {@code bootui.fault-tolerance.enabled}, or no recorder bean present).
     */
    private List<FaultToleranceEventRecorder.CapturedEvent> faultToleranceCaptured() {
        if (!properties.isPanelEnabled(BootUiPanels.FAULT_TOLERANCE)) {
            return null;
        }
        FaultToleranceEventRecorder recorder = faultToleranceEvents.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.recent();
    }

    private List<JmsActivityRecorder.CapturedMessage> jmsMessages() {
        if (!properties.isPanelEnabled(BootUiPanels.JMS)) {
            return null;
        }
        JmsActivityRecorder recorder = jmsActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.recent();
    }

    /**
     * Recent RabbitMQ messages feeding the assembler's {@code MESSAGING} entries, or {@code null} when
     * the source is not feeding (dedicated RabbitMQ panel disabled, capture disabled via
     * {@code bootui.rabbitmq.enabled}, or no recorder bean present).
     */
    private List<RabbitActivityRecorder.CapturedMessage> rabbitMessages() {
        if (!properties.isPanelEnabled(BootUiPanels.RABBITMQ)) {
            return null;
        }
        RabbitActivityRecorder recorder = rabbitActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        return recorder.recent();
    }

    /**
     * Full detail (group + every occurrence) for each currently-grouped exception, so the profile
     * drill-down can match on any occurrence's trace id — not just each group's most recent one.
     */
    private List<ExceptionDetailDto> allExceptionDetails() {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return List.of();
        }
        ExceptionStore store = exceptionStoreProvider.getIfAvailable();
        if (store == null) {
            return List.of();
        }
        List<ExceptionDetailDto> details = new ArrayList<>();
        for (ExceptionGroupDto group : exceptionsService.report(store).groups()) {
            ExceptionStore.GroupDetail detail = store.find(group.id());
            if (detail != null) {
                details.add(exceptionsService.detail(detail));
            }
        }
        return details;
    }

    private String currentHealthStatus() {
        if (!properties.isPanelEnabled(BootUiPanels.HEALTH)) {
            return null;
        }
        HealthController controller = health.getIfAvailable();
        if (controller == null) {
            return null;
        }
        try {
            HealthNodeDto node = controller.health();
            return node == null ? null : node.status();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private TraceDetailDto correlateTrace(String traceId) {
        if (traceId == null || traceId.isBlank() || !properties.isPanelEnabled(BootUiPanels.TRACES)) {
            return null;
        }
        TracesController controller = traces.getIfAvailable();
        if (controller == null) {
            return null;
        }
        try {
            TraceDetailDto detail = controller.detail(traceId);
            return detail == null || detail.spans().isEmpty() ? null : detail;
        } catch (RuntimeException ex) {
            // Trace not found or filtered out; correlation simply has no trace tier.
            return null;
        }
    }

    /** SQL trace snapshot for one request cycle: entries plus whether the source is present and feeding. */
    private record SqlSnapshot(List<SqlTraceEntryDto> entries, boolean available, String unavailableWarning) {}
}
