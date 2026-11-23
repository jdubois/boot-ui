package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiEngineConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryOriginDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearResult;
import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourcesDto;
import io.github.jdubois.bootui.engine.activity.ActivityCapture;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
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
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RequestProfileSelection;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private DurableJournalSettings durableJournalSettings;
    private ObjectProvider<DurableJournalStore> durableJournalStores;
    private ExceptionStore durableExceptionStore;
    private DurableJournalRuntime durableJournalRuntime = new DurableJournalRuntime();
    private volatile boolean deferredDurableCapture;
    private final ReservedActivityEntries reservedEntries;
    private final BootUiProperties properties;
    private final ActivityFeedSource feedSource;
    private volatile JournalActivityReports journalReports;
    private volatile RuntimeJournal captureJournal;
    private volatile Supplier<RouteTemplateResolver> captureRoutes;
    private volatile ActivityPersistenceSettings deferredCapture;
    private volatile RequestJournalProfiles requestJournalProfiles;
    private volatile ExposurePolicy exposure;
    /** Reads the live exposure policy on every call, so the journal projections built once follow a live change. */
    private final ExposurePolicy liveExposure = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return exposure.valueExposure();
        }

        @Override
        public boolean maskSecrets() {
            return exposure.maskSecrets();
        }
    };

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
        this.exposure = new BootUiExposure(properties);
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

    @Autowired
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
            BootUiProperties properties,
            ObjectProvider<DurableJournalSettings> durableJournalSettings,
            ObjectProvider<DurableJournalStore> durableJournalStores,
            ObjectProvider<DurableJournalRuntime> durableJournalRuntime) {
        this(
                httpExchanges,
                sqlTrace,
                restClientTrace,
                exceptions,
                securityLogs,
                traces,
                health,
                email,
                sqlTraceRecorder,
                restClientTraceRecorder,
                exceptionStore,
                requestCorrelations,
                securityCorrelations,
                cacheActivity,
                scheduledTaskRuns,
                kafkaActivityRecorder,
                jmsActivityRecorder,
                rabbitActivityRecorder,
                faultToleranceEventRecorder,
                emailCaptureService,
                activityStore,
                persistenceSettings,
                dataSourceProvider,
                properties,
                durableJournalSettings.getIfAvailable(),
                durableJournalStores,
                durableJournalRuntime.getIfAvailable());
    }

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
            BootUiProperties properties,
            DurableJournalSettings durableJournalSettings,
            ObjectProvider<DurableJournalStore> durableJournalStores,
            DurableJournalRuntime durableJournalRuntime) {
        this(
                httpExchanges,
                sqlTrace,
                restClientTrace,
                exceptions,
                securityLogs,
                traces,
                health,
                email,
                sqlTraceRecorder,
                restClientTraceRecorder,
                exceptionStore,
                requestCorrelations,
                securityCorrelations,
                cacheActivity,
                scheduledTaskRuns,
                kafkaActivityRecorder,
                jmsActivityRecorder,
                rabbitActivityRecorder,
                faultToleranceEventRecorder,
                emailCaptureService,
                activityStore,
                persistenceSettings,
                dataSourceProvider,
                properties);
        this.durableJournalSettings = durableJournalSettings;
        this.durableJournalStores = durableJournalStores;
        this.durableJournalRuntime =
                durableJournalRuntime == null ? new DurableJournalRuntime() : durableJournalRuntime;
        this.durableExceptionStore = exceptionStore.getIfAvailable();
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
                        InsightsStack.SPRING_MVC,
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
        JournalActivityFeed.Filter filter =
                new JournalActivityFeed.Filter(type, severity, since, route, run, requestId, noRequest);
        LiveActivityReport live = live(source, filter, limit);
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
        // backend) serves entries and pagination, so recently captured entries are visible immediately
        // and the dashboard can page back through history beyond what fits in memory. KPIs/type counts/
        // sources/warnings stay computed from the current live merge above — that strip is an "at a
        // glance, right now" summary, not scoped to whichever historical page happens to be browsed.
        ActivityQuery query = new ActivityQuery(
                persistenceSettings.instanceId(), type, severity, q, since > 0 ? since : null, until, cursor, pageSize);
        JournalTextExposure storedRule = JournalTextExposure.of(liveExposure);
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
        DurableJournalGlobalCursor decodedCursor;
        try {
            decodedCursor = cursor == null || cursor.isBlank() ? null : DurableJournalGlobalCursor.decode(cursor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid durable journal cursor", ex);
        }
        DurableJournalFeedQuery query = new DurableJournalFeedQuery(
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
                decodedCursor,
                pageSize);
        try {
            DurableJournalFeedPage page = durableJournalRuntime.store().queryFeed(query);
            JournalTextExposure storedRule = JournalTextExposure.of(liveExposure);
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

    /**
     * Installs the live exposure policy journal-rendered SQL, log text, and paths are shown under ({@code PLAN-v2} §8).
     * Without it they follow the bound {@code bootui.expose-values} and {@code bootui.mask-secrets}.
     */
    @Autowired(required = false)
    public void setExposure(BootUiExposure exposure) {
        if (exposure != null) {
            this.exposure = exposure;
        }
    }

    /** Installs the Java Agent service, which says whether the request profile's {@code PROPAGATED} tier applies. */
    @Autowired(required = false)
    public void setJavaAgent(ObjectProvider<JavaAgentService> javaAgent) {
        correlator.setJavaAgent(javaAgent);
    }

    /** Installs the runtime journal whose status block and <b>Clear recording</b> this panel serves. */
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
                        liveExposure)
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
                liveExposure);
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
                    liveExposure);
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
                            liveExposure)
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

    @GetMapping("/request/{id}")
    public RequestProfileDto request(@PathVariable("id") String id) {
        return correlator.profile(id);
    }

    public RequestProfileSelectionDto agentProfile(String id) {
        return RequestProfileSelection.select(id, this::requestJournal, this::request);
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
