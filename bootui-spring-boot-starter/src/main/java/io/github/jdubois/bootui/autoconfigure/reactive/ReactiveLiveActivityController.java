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
import io.github.jdubois.bootui.core.dto.ActivityEntryOriginDto;
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
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.journal.ActivityFeedSource;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.DurableJournalCapture;
import io.github.jdubois.bootui.engine.journal.DurableJournalCaptureSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.DurableJournalEvidence;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedItem;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedPage;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedQuery;
import io.github.jdubois.bootui.engine.journal.DurableJournalGlobalCursor;
import io.github.jdubois.bootui.engine.journal.DurableJournalRunMetadata;
import io.github.jdubois.bootui.engine.journal.DurableJournalRuntime;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStore;
import io.github.jdubois.bootui.engine.journal.DurableJournalStoreException;
import io.github.jdubois.bootui.engine.journal.DurableJournalUnavailableException;
import io.github.jdubois.bootui.engine.journal.ExceptionStoreDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.HttpPayloadDurableJournalDetailProvider;
import io.github.jdubois.bootui.engine.journal.JdbcDurableJournalStore;
import io.github.jdubois.bootui.engine.journal.JournalActivityCapture;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEvidenceSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalRowDetails;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RequestProfileSelection;
import io.github.jdubois.bootui.engine.journal.RunHistory;
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
    private DurableJournalSettings durableJournalSettings;
    private ObjectProvider<DurableJournalStore> durableJournalStores;
    private ExceptionStore durableExceptionStore;
    private DurableJournalRuntime durableJournalRuntime = new DurableJournalRuntime();
    private volatile boolean deferredDurableCapture;

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

    @Autowired
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
            BootUiExposure exposure,
            ObjectProvider<DurableJournalSettings> durableJournalSettings,
            ObjectProvider<DurableJournalStore> durableJournalStores,
            ObjectProvider<DurableJournalRuntime> durableJournalRuntime) {
        this(
                httpExchanges,
                sqlTraceRecorder,
                restClientTrace,
                dataSourceProvider,
                exceptionStoreProvider,
                scheduledTaskActivity,
                securityLogs,
                traces,
                health,
                email,
                emailCaptureService,
                cacheActivity,
                kafkaActivity,
                jmsActivity,
                faultToleranceEvents,
                rabbitActivity,
                activityStore,
                persistenceSettings,
                properties,
                exposure,
                durableJournalSettings.getIfAvailable(),
                durableJournalStores,
                durableJournalRuntime.getIfAvailable());
    }

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
            BootUiExposure exposure,
            DurableJournalSettings durableJournalSettings,
            ObjectProvider<DurableJournalStore> durableJournalStores,
            DurableJournalRuntime durableJournalRuntime) {
        this(
                httpExchanges,
                sqlTraceRecorder,
                restClientTrace,
                dataSourceProvider,
                exceptionStoreProvider,
                scheduledTaskActivity,
                securityLogs,
                traces,
                health,
                email,
                emailCaptureService,
                cacheActivity,
                kafkaActivity,
                jmsActivity,
                faultToleranceEvents,
                rabbitActivity,
                activityStore,
                persistenceSettings,
                properties,
                exposure);
        this.durableJournalSettings = durableJournalSettings;
        this.durableJournalStores = durableJournalStores;
        this.durableJournalRuntime =
                durableJournalRuntime == null ? new DurableJournalRuntime() : durableJournalRuntime;
        this.durableExceptionStore = exceptionStoreProvider.getIfAvailable();
        if (persistenceSettings.enabled()) {
            deferredCapture = null;
            deferredDurableCapture = true;
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
            ActivityCapture capture = startCapture(activityStore, settings, current);
            unsubscribers.add(capture::close);
            return capture;
        }
        log.warn("Live Activity persistence is enabled, but the runtime journal is disabled"
                + " (bootui.runtime-journal.enabled=false), so no durable history is written: the journal is its only"
                + " source in 2.0.");
        return null;
    }

    private DurableJournalCapture startDurablePersistence(RuntimeJournal journal, DurableJournalStore store) {
        store.verifySchema();
        List<DurableJournalDetailProvider> providers = new ArrayList<>();
        providers.add(new HttpPayloadDurableJournalDetailProvider());
        if (durableExceptionStore != null) {
            providers.add(new ExceptionStoreDurableJournalDetailProvider(durableExceptionStore));
        }
        return DurableJournalCapture.start(
                journal,
                durableJournalSettings,
                store,
                new JournalActivityFeed(
                        properties.getActivity().getRequestSlowThresholdMs(),
                        properties.getActivity().getNPlusOneThreshold(),
                        captureRoutes),
                DurableJournalCaptureSettings.of(durableJournalSettings),
                List.copyOf(providers),
                phase -> DurableJournalRunMetadata.snapshot(
                        phase,
                        journal,
                        durableJournalSettings.identity(),
                        InsightsStack.SPRING_WEBFLUX,
                        Map.of(),
                        null,
                        phase == DurableJournalRunMetadata.Phase.END
                                ? RunHistory.shared().summaries().stream()
                                        .filter(summary -> summary.header()
                                                .runId()
                                                .equals(journal.run().id()))
                                        .findFirst()
                                        .orElse(null)
                                : null));
    }

    private boolean activateDurablePersistence(DurableJournalStore store, DurableJournalCapture capture) {
        return durableJournalRuntime.activate(store, capture);
    }

    private boolean durablePersistenceActive() {
        return durableJournalRuntime.active();
    }

    private ActivityCapture startCapture(
            ActivityStore target, ActivityPersistenceSettings settings, RuntimeJournal journal) {
        return JournalActivityCapture.start(
                target,
                settings,
                reservedEntries,
                journal,
                new JournalActivityFeed(
                        properties.getActivity().getRequestSlowThresholdMs(),
                        properties.getActivity().getNPlusOneThreshold(),
                        captureRoutes),
                properties::isPanelEnabled);
    }

    /**
     * Reports a capture the journal never arrived for, once every setter has run. An initialization
     * callback rather than a context event, so this lazy controller is still created only when first used.
     */
    @Override
    public void afterPropertiesSet() {
        if (deferredDurableCapture) {
            deferredDurableCapture = false;
            log.warn("Live Activity persistence is enabled, but no runtime journal was installed, so no durable"
                    + " history is written: the journal is its only source in 2.0.");
        }
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
        synchronized (activityStore) {
            captureJournal = null;
            unsubscribers.forEach(Runnable::run);
            unsubscribers.clear();
            changeStream.close();
        }
    }

    /** The feed from the configured source, without the journal-only filters; for callers such as the MCP tools. */
    public LiveActivityReport activity(
            String type, String severity, long since, int limit, String q, Long until, String cursor, int pageSize) {
        return activity(type, severity, since, limit, q, until, cursor, pageSize, null, null, null, null, false);
    }

    public LiveActivityReport activity(
            String type,
            String severity,
            long since,
            int limit,
            String q,
            Long until,
            String cursor,
            int pageSize,
            String source,
            String route,
            String run,
            String requestId,
            boolean noRequest) {
        return activity(
                type, severity, since, limit, q, until, cursor, pageSize, source, route, null, null, null, run,
                requestId, noRequest);
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
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "application", required = false) String application,
            @RequestParam(name = "instance", required = false) String instance,
            @RequestParam(name = "run", required = false) String run,
            @RequestParam(name = "requestId", required = false) String requestId,
            @RequestParam(name = "noRequest", required = false, defaultValue = "false") boolean noRequest) {
        LiveActivityReport live = live(
                source, new JournalActivityFeed.Filter(type, severity, since, route, run, requestId, noRequest), limit);
        ActivityFeedSource resolvedSource = parseFeedSource(source);
        ActivityPersistenceOptionDto persistenceOption = new ActivityPersistenceOptionDto(
                durablePersistenceActive() || activityStore.persistent(),
                BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider) != null,
                durableJournalSettings == null
                        ? persistenceSettings.tableName()
                        : durableJournalSettings.tablePrefix());
        if (durablePersistenceActive() && resolvedSource == ActivityFeedSource.JOURNAL) {
            return durableActivity(
                    live,
                    persistenceOption,
                    namespace,
                    application,
                    instance,
                    run,
                    type,
                    severity,
                    q,
                    since,
                    until,
                    cursor,
                    pageSize,
                    route,
                    requestId,
                    noRequest);
        }
        if (!activityStore.persistent() || durableJournalSettings != null) {
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
        JournalTextExposure storedRule = JournalTextExposure.of(exposure);
        ActivityPage page = io.github.jdubois.bootui.engine.activity.ReadableActivityPages.query(
                activityStore,
                query,
                row -> JournalSourcePanels.isReadable(row, properties::isPanelEnabled),
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

    private LiveActivityReport durableActivity(
            LiveActivityReport live,
            ActivityPersistenceOptionDto persistenceOption,
            String namespace,
            String application,
            String instance,
            String run,
            String type,
            String severity,
            String q,
            long since,
            Long until,
            String cursor,
            int pageSize,
            String route,
            String requestId,
            boolean noRequest) {
        String selectedNamespace =
                blankToNull(namespace) == null ? durableJournalSettings.namespaceId() : namespace.strip();
        String selectedApplication =
                blankToNull(application) == null ? durableJournalSettings.applicationId() : application.strip();
        if (!selectedNamespace.equals(durableJournalSettings.namespaceId())
                && !durableJournalSettings.readerArchiveCrossNamespace()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Durable journal namespace selection is disabled by configuration");
        }
        DurableJournalFeedQuery query;
        try {
            query = new DurableJournalFeedQuery(
                    selectedNamespace,
                    selectedApplication,
                    run,
                    instance,
                    null,
                    type,
                    severity,
                    q,
                    route,
                    requestId,
                    since > 0 ? since : null,
                    until,
                    noRequest,
                    DurableJournalGlobalCursor.decodeStrict(cursor),
                    pageSize);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
        try {
            DurableJournalFeedPage page = durableJournalRuntime.store().queryFeed(query);
            JournalTextExposure storedRule = JournalTextExposure.of(exposure);
            List<io.github.jdubois.bootui.core.dto.ActivityEntryDto> entries = new ArrayList<>();
            List<ActivityEntryOriginDto> origins = new ArrayList<>();
            for (DurableJournalFeedItem item : page.entries()) {
                if (item.projectionAvailable()
                        && JournalSourcePanels.isReadable(item.activity(), properties::isPanelEnabled)) {
                    var entry = storedRule.reapply(item.activity());
                    entries.add(entry);
                    origins.add(new ActivityEntryOriginDto(
                            entry.id(),
                            item.origin().scope().namespace(),
                            item.origin().scope().application(),
                            item.origin().scope().run(),
                            item.origin().instanceId(),
                            item.source(),
                            item.journalSequence(),
                            item.globalSequence()));
                }
            }
            return new LiveActivityReport(
                    live.available(),
                    entries,
                    live.typeCounts(),
                    live.kpis(),
                    live.sources(),
                    live.warnings(),
                    new ActivityPageInfo(true, page.nextCursorToken(), page.hasMore()),
                    persistenceOption,
                    origins);
        } catch (DurableJournalStoreException ex) {
            if (cursor == null || cursor.isBlank()) {
                String selectedRun = blankToNull(run);
                String selectedInstance = blankToNull(instance);
                if (!selectedNamespace.equals(durableJournalSettings.namespaceId())
                        || !selectedApplication.equals(durableJournalSettings.applicationId())
                        || selectedInstance != null && !selectedInstance.equals(durableJournalSettings.instanceId())
                        || selectedRun != null && !selectedRun.equals(durableJournalSettings.runId())) {
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "Durable Live Activity history is unavailable", ex);
                }
                List<String> warnings = new ArrayList<>(live.warnings());
                warnings.add("Durable Live Activity history is unavailable; showing the current run from memory.");
                log.warn("Durable Live Activity read failed; serving the current run from the runtime journal", ex);
                return new LiveActivityReport(
                        live.available(),
                        live.entries(),
                        live.typeCounts(),
                        live.kpis(),
                        live.sources(),
                        warnings,
                        null,
                        persistenceOption);
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Durable Live Activity history is unavailable", ex);
        }
    }

    private ActivityFeedSource parseFeedSource(String source) {
        try {
            return ActivityFeedSource.parse(source, feedSource);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private volatile RuntimeJournalService runtimeJournal = new RuntimeJournalService(null, null);
    private volatile RuntimeJournal statusJournal;
    private volatile JournalAggregates statusAggregates;
    private volatile AgentEvidence agentEvidence;

    /**
     * Installs the BootUI agent's evidence kept outside the journal (M5-11), which the journal status reports beside its
     * own bytes and which every clear of the journal clears.
     */
    @Autowired(required = false)
    public void setAgentEvidence(AgentEvidence agentEvidence) {
        this.agentEvidence = agentEvidence;
        this.runtimeJournal = new RuntimeJournalService(statusJournal, statusAggregates, agentEvidence);
    }

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
        this.statusJournal = journal;
        this.statusAggregates = aggregates;
        this.runtimeJournal = new RuntimeJournalService(journal, aggregates, agentEvidence);
        this.captureJournal = journal;
        this.captureRoutes = aggregates == null ? null : aggregates.declaredRoutes();
        if (deferredDurableCapture && journal != null) {
            deferredDurableCapture = false;
            if (!durablePersistenceActive() && journal.settings().enabled() && journal.isOpen()) {
                try {
                    DurableJournalStore store =
                            durableJournalStores == null ? null : durableJournalStores.getIfAvailable();
                    if (store == null) {
                        log.warn(
                                "Live Activity persistence is enabled, but no durable journal store could be resolved.");
                    } else {
                        activateDurablePersistence(store, startDurablePersistence(journal, store));
                    }
                } catch (RuntimeException ex) {
                    log.warn(
                            "Live Activity durable journal capture could not start; the live in-memory endpoint"
                                    + " remains active.",
                            ex);
                }
            } else if (!durablePersistenceActive()) {
                log.warn("Live Activity persistence is enabled, but the runtime journal is disabled or closed;"
                        + " the live in-memory endpoint remains active.");
            }
        }
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
        return runtimeJournal.status(
                durableJournalRuntime.active() ? durableJournalRuntime.capture().status() : null);
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
     * idempotency, journal capture startup on success), which applies unchanged here.
     */
    @PostMapping("/use-existing-datasource")
    public ResponseEntity<ActivitySwitchResult> useExistingDatasource(
            @RequestBody(required = false) ActivitySwitchRequest request) {
        DataSource dataSource = BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider);
        synchronized (activityStore) {
            RuntimeJournal journal = captureJournal;
            if (durableJournalSettings != null) {
                return useExistingDatasourceDurable(dataSource, request, journal);
            }
            ActivitySwitchResponse response = new ActivitySwitchService()
                    .useExistingDataSource(
                            activityStore,
                            persistenceSettings,
                            dataSource,
                            request,
                            journal,
                            (target, settings) -> startCapture(target, settings, journal));
            if (response.capture() != null) {
                unsubscribers.add(response.capture()::close);
            }
            return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
        }
    }

    private ResponseEntity<ActivitySwitchResult> useExistingDatasourceDurable(
            DataSource dataSource, ActivitySwitchRequest request, RuntimeJournal journal) {
        if (durablePersistenceActive()) {
            return ResponseEntity.ok(new ActivitySwitchResult(
                    "already-active",
                    "Live Activity is already using durable persistence.",
                    durableJournalSettings.tablePrefix()));
        }
        if (dataSource == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ActivitySwitchResult(
                            "unavailable",
                            "No DataSource is available to reuse; configure one, or set"
                                    + " bootui.activity.persistence.enabled=true with a dedicated JDBC URL instead.",
                            durableJournalSettings.tablePrefix()));
        }
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return ResponseEntity.badRequest()
                    .body(new ActivitySwitchResult(
                            "blocked",
                            "Action requires confirm=true because it creates a database table and starts writing"
                                    + " to it.",
                            durableJournalSettings.tablePrefix()));
        }
        if (journal == null || !journal.settings().enabled() || !journal.isOpen()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ActivitySwitchResult(
                            "unavailable",
                            "Live Activity cannot start durable history because the runtime journal is unavailable,"
                                    + " disabled, or closed. In-memory storage remains active.",
                            durableJournalSettings.tablePrefix()));
        }
        JdbcDurableJournalStore candidate =
                new JdbcDurableJournalStore(dataSource, durableJournalSettings.tablePrefix());
        DurableJournalCapture capture = null;
        try {
            capture = startDurablePersistence(journal, candidate);
            DurableJournalCapture started = capture;
            boolean published = journal.commitWhileOpen(() -> activateDurablePersistence(candidate, started));
            if (!published) {
                capture.close();
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ActivitySwitchResult(
                                "unavailable",
                                "The runtime journal closed before durable capture could be published."
                                        + " In-memory storage remains active.",
                                durableJournalSettings.tablePrefix()));
            }
            return ResponseEntity.ok(new ActivitySwitchResult(
                    "success",
                    "Live Activity is now saving durable journal history to \""
                            + durableJournalSettings.tablePrefix()
                            + "\" tables. This switch applies to this running instance only; configure"
                            + " bootui.activity.persistence.enabled=true to persist it across restarts.",
                    durableJournalSettings.tablePrefix()));
        } catch (RuntimeException ex) {
            if (capture != null) {
                capture.close();
            }
            log.warn(
                    "Failed to switch to durable Live Activity history; the live in-memory endpoint remains active",
                    ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ActivitySwitchResult(
                            "failed",
                            "Failed to switch to durable Live Activity history: " + ex.getMessage()
                                    + ". In-memory storage remains active.",
                            durableJournalSettings.tablePrefix()));
        }
    }

    /**
     * The trace-id-only per-request profile drill-down, served by the shared
     * {@link ExecutionProfileAssembler}. The serving-thread and time-window tiers are reported unavailable:
     * no request served on the event loop has a stable owning thread to correlate on. Every source is read
     * through the same masked, self-filtered bean its own panel uses, and only when that panel is enabled.
     */
    /**
     * One request as the runtime journal recorded it ({@code docs/PLAN-v2.md} §5.3, §5.11): its timeline, GC pauses,
     * measured resources, route comparison, baseline comparison, and touched resources.
     */
    @GetMapping("/request/{id}/journal")
    public RequestJournalProfileDto requestJournal(
            @PathVariable("id") String id,
            @RequestParam(value = "compareWith", required = false) String compareWith,
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestParam(value = "application", required = false) String application,
            @RequestParam(value = "run", required = false) String run,
            @RequestParam(value = "instance", required = false) String instance) {
        RequestJournalProfiles profiles = requestJournalProfiles;
        if (profiles == null) {
            profiles = new RequestJournalProfiles(
                    null,
                    null,
                    properties.getActivity().getRequestSlowThresholdMs(),
                    properties.getActivity().getNPlusOneThreshold(),
                    properties::isPanelEnabled,
                    exposure);
        }
        try {
            DurableProfileRead evidence = durableProfileEvidence(namespace, application, run, instance);
            if (evidence == null) {
                return profiles.profile(id, compareWith);
            }
            RequestJournalProfiles selected = evidence.historical()
                    ? new RequestJournalProfiles(
                            null,
                            null,
                            properties.getActivity().getRequestSlowThresholdMs(),
                            properties.getActivity().getNPlusOneThreshold(),
                            properties::isPanelEnabled,
                            exposure)
                    : profiles;
            return selected.profileFrom(evidence.snapshot(), id, compareWith);
        } catch (DurableJournalUnavailableException ex) {
            return RequestJournalProfileDto.unavailable(id, ex.getMessage());
        } catch (DurableJournalStoreException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Durable request profile evidence is unavailable", ex);
        }
    }

    public RequestJournalProfileDto requestJournal(String id, String compareWith) {
        return requestJournal(id, compareWith, null, null, null, null);
    }

    private DurableProfileRead durableProfileEvidence(
            String namespace, String application, String run, String instance) {
        boolean requested = hasText(namespace) || hasText(application) || hasText(run) || hasText(instance);
        if (durableJournalSettings == null) {
            if (requested) {
                throw new DurableJournalUnavailableException("Durable journal archive settings are unavailable");
            }
            return null;
        }
        DurableJournalScope selected = selectedProfileScope(namespace, application, run);
        boolean historical = !selected.equals(durableJournalSettings.scope())
                || hasText(instance) && !instance.strip().equals(durableJournalSettings.instanceId());
        if (historical && (durableJournalRuntime == null || !durableJournalRuntime.active())) {
            throw new DurableJournalUnavailableException("Durable journal evidence is unavailable for " + selected);
        }
        JournalEvidenceSnapshot evidence = historical
                ? new DurableJournalEvidence(durableJournalRuntime.store()).read(selected)
                : durableJournalRuntime == null
                        ? null
                        : durableJournalRuntime.readCurrentEvidence(statusJournal, selected);
        if (evidence == null) {
            return null;
        }
        if (hasText(instance) && !instance.strip().equals(evidence.status().instanceId())) {
            throw new DurableJournalUnavailableException(
                    "Durable journal run " + selected + " is not from instance " + instance.strip());
        }
        return new DurableProfileRead(evidence, historical);
    }

    private DurableJournalScope selectedProfileScope(String namespace, String application, String run) {
        String selectedNamespace = profileValueOr(namespace, durableJournalSettings.namespaceId());
        String selectedApplication = profileValueOr(application, durableJournalSettings.applicationId());
        String selectedRun = profileValueOr(run, durableJournalSettings.runId());
        if (!selectedNamespace.equals(durableJournalSettings.namespaceId())
                && !durableJournalSettings.readerArchiveCrossNamespace()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Durable journal namespace selection is disabled by configuration");
        }
        try {
            return new DurableJournalScope(selectedNamespace, selectedApplication, selectedRun);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid durable journal scope", ex);
        }
    }

    private static String profileValueOr(String value, String fallback) {
        return hasText(value) ? value.strip() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DurableProfileRead(JournalEvidenceSnapshot snapshot, boolean historical) {}

    public RequestJournalProfileDto requestJournal(String id) {
        return requestJournal(id, null);
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
