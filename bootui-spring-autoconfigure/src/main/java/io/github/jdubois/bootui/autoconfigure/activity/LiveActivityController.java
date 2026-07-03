package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiEngineConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
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
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.engine.activity.ActivityCaptureFactory;
import io.github.jdubois.bootui.engine.activity.ActivityCapturePoller;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-only Live Activity endpoints: a merged, reverse-chronological stream of recent application
 * activity plus a Symfony-style per-request profile. Both reuse BootUI's existing signal controllers
 * so masking, self-filtering and buffer bounds are inherited; this controller adds no instrumentation.
 *
 * <p>Because the merged feed is genuinely event-driven, the panel refreshes over Server-Sent Events
 * instead of fixed-interval polling: {@link #stream()} pushes a tiny coalesced {@code update} tick
 * whenever any underlying source changes, and the browser re-fetches {@link #activity} so all
 * masking, filtering and bounds still apply. The four sources are wired in as signals — SQL trace and
 * exceptions through their in-process subscribe hooks, security and HTTP requests through Spring
 * application events — and a single {@link BootUiChangeStream} coalesces a burst into one push.
 *
 * <p>This controller also always owns the capture side: whenever the injected {@link
 * #persistenceSettings} has persistence enabled (from startup configuration, or later via the "Use the
 * existing datasource" switch — see {@link #useExistingDatasource}), it stamps and periodically appends
 * whatever {@link #service}'s merged feed has not yet captured (see {@link ActivityCaptureFactory}) into
 * the shared {@link SwitchableActivityStore} bean, and {@link #activity} then serves entries and
 * pagination from that store — which itself merges its in-memory hot cache with the durable backend —
 * instead of from a fresh live re-merge. The store bean always exists (even with persistence disabled,
 * as a bare in-memory store), so {@link #activity} branches on the store's own live {@code
 * persistent()} state rather than the static startup settings, correctly reflecting a runtime switch.
 */
@RestController
@RequestMapping("/bootui/api/activity")
public class LiveActivityController {

    private final LiveActivityService service;
    private final LiveActivityCorrelator correlator;
    private final SecurityEventCorrelationRegistry securityCorrelations;
    private final BootUiChangeStream changeStream;
    private final List<Runnable> unsubscribers = Collections.synchronizedList(new ArrayList<>());
    private final String selfPath;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public LiveActivityController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<HealthController> health,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiProperties properties) {
        this.service = new LiveActivityService(
                httpExchanges,
                sqlTrace,
                exceptions,
                securityLogs,
                health,
                requestCorrelations,
                securityCorrelations,
                properties);
        this.correlator = new LiveActivityCorrelator(
                httpExchanges,
                sqlTrace,
                exceptions,
                securityLogs,
                traces,
                requestCorrelations,
                securityCorrelations,
                properties);
        this.securityCorrelations = securityCorrelations.getIfAvailable();
        this.changeStream = new BootUiChangeStream("activity");
        this.selfPath = properties.getPath();
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder != null) {
            unsubscribers.add(recorder.subscribe(changeStream::signal));
        }
        ExceptionStore store = exceptionStore.getIfAvailable();
        if (store != null) {
            unsubscribers.add(store.subscribe(changeStream::signal));
        }
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.dataSourceProvider = dataSourceProvider;
        if (persistenceSettings.enabled()) {
            // Capture side of the persistence option: poll the same merged feed the panel itself reads,
            // stamping and appending whatever has not already been captured. Reusing this.service::report
            // (rather than re-reading the four signal sources) means self-filtering/masking/bounds are
            // inherited identically, and no new low-level instrumentation is needed.
            ActivityCapturePoller poller = ActivityCaptureFactory.start(
                    activityStore,
                    persistenceSettings,
                    () -> service.report(null, null, 0, 0).entries());
            unsubscribers.add(poller::close);
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
     * The capture poller (when persistence is enabled) is stopped the same way, for the same reason;
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

    @GetMapping
    public LiveActivityReport activity(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "since", required = false, defaultValue = "0") long since,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "until", required = false) Long until,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false, defaultValue = "0") int pageSize) {
        LiveActivityReport live = service.report(type, severity, since, limit);
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

    /**
     * Hot-switches Live Activity from in-memory to durable JDBC persistence, reusing the host
     * application's own {@code DataSource} — no restart required. Gated by explicit confirmation (this
     * creates a database table and starts writing to it) and by BootUI's global/per-panel read-only
     * filter, like every other mutating panel action. Idempotent: calling this when persistence is
     * already active is a no-op that reports success rather than an error. On success, starts this
     * controller's own capture poller against the newly durable store, exactly as the constructor would
     * have done had persistence been enabled from startup.
     */
    @PostMapping("/use-existing-datasource")
    public ResponseEntity<ActivitySwitchResult> useExistingDatasource(
            @RequestBody(required = false) ActivitySwitchRequest request) {
        DataSource dataSource = BootUiEngineConfiguration.resolveActivityDataSource(dataSourceProvider);
        ActivitySwitchResponse response = new ActivitySwitchService()
                .useExistingDataSource(activityStore, persistenceSettings, dataSource, request);
        if (response.newSettings() != null) {
            ActivityCapturePoller poller = ActivityCaptureFactory.start(
                    activityStore,
                    response.newSettings(),
                    () -> service.report(null, null, 0, 0).entries());
            unsubscribers.add(poller::close);
        }
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
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
