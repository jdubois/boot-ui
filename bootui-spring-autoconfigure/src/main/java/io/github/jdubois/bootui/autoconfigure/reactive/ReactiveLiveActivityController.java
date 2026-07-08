package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiEngineConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.ActivitySwitchResult;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
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
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.RequestProfileAssembler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
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
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code LiveActivityController}. Unlike the servlet controller (which
 * correlates signals to a request using thread-per-request heuristics — serving thread, time window —
 * that have no equivalent on Reactor Netty's event-loop model), this controller reuses the
 * framework-neutral {@link LiveActivityAssembler}/{@link RequestProfileAssembler} pair the Quarkus adapter
 * already validated for exactly this constraint: correlation is driven purely by a shared distributed
 * trace id (see {@code TraceIdProvider}), and a request with no trace id simply renders flat/unprofileable
 * rather than guessing.
 *
 * <p>All five signal sources are read directly from the already-reactive, already-masked/self-filtered
 * beans this adapter wires for their own panels — {@link HttpExchangesController} (HTTP requests, shared
 * with the servlet adapter since it depends only on the stack-agnostic Actuator
 * {@code HttpExchangeRepository}), {@link SqlTraceRecorder} (SQL trace), {@link ExceptionStore} (exceptions),
 * {@code ReactiveSecurityLogsController} (security/audit events), and {@link KafkaActivityRecorder}
 * (captured producer/consumer metadata) — so this controller adds no new capture instrumentation of its
 * own, only the merge. Because those beans are reached directly (bypassing the HTTP layer, and with it
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
 * completes, and the SQL trace recorder / exception store / Kafka recorder subscriptions signal directly,
 * same as servlet.
 */
@RestController
@RequestMapping("/bootui/api/activity")
public class ReactiveLiveActivityController {

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceRecorder> sqlTraceRecorder;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<ExceptionStore> exceptionStoreProvider;
    private final ObjectProvider<ReactiveSecurityLogsController> securityLogs;
    private final ObjectProvider<TracesController> traces;
    private final ObjectProvider<HealthController> health;
    private final ObjectProvider<KafkaActivityRecorder> kafkaActivity;
    private final BootUiProperties properties;
    private final BootUiExposure exposure;
    private final ExceptionsService exceptionsService;
    private final ReactiveBootUiChangeStream changeStream;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final RequestProfileAssembler profileAssembler = new RequestProfileAssembler();
    private final List<Runnable> unsubscribers = Collections.synchronizedList(new ArrayList<>());

    public ReactiveLiveActivityController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<ExceptionStore> exceptionStoreProvider,
            ObjectProvider<ReactiveSecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<HealthController> health,
            ObjectProvider<KafkaActivityRecorder> kafkaActivity,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            BootUiProperties properties,
            BootUiExposure exposure) {
        this.httpExchanges = httpExchanges;
        this.sqlTraceRecorder = sqlTraceRecorder;
        this.dataSourceProvider = dataSourceProvider;
        this.exceptionStoreProvider = exceptionStoreProvider;
        this.securityLogs = securityLogs;
        this.traces = traces;
        this.health = health;
        this.kafkaActivity = kafkaActivity;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.properties = properties;
        this.exposure = exposure;
        this.exceptionsService = new ExceptionsService(exposure);
        this.changeStream = new ReactiveBootUiChangeStream("activity");
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder != null) {
            unsubscribers.add(recorder.subscribe(changeStream::signal));
        }
        ExceptionStore store = exceptionStoreProvider.getIfAvailable();
        if (store != null) {
            unsubscribers.add(store.subscribe(changeStream::signal));
        }
        KafkaActivityRecorder kafkaRecorder = kafkaActivity.getIfAvailable();
        if (kafkaRecorder != null) {
            unsubscribers.add(kafkaRecorder.subscribe(changeStream::signal));
        }
        if (persistenceSettings.enabled()) {
            // Capture side of the persistence option: poll the same merged feed the panel itself reads,
            // stamping and appending whatever has not already been captured. See LiveActivityController's
            // matching constructor logic for why this reuses mergedReport rather than re-reading sources.
            ActivityCapturePoller poller = ActivityCaptureFactory.start(
                    activityStore, persistenceSettings, () -> mergedReport(0).entries());
            unsubscribers.add(poller::close);
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
        LiveActivityReport live = mergedReport(limit);
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
                page.entryDtos(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistenceOption);
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
            ActivityCapturePoller poller = ActivityCaptureFactory.start(
                    activityStore, response.newSettings(), () -> mergedReport(0).entries());
            unsubscribers.add(poller::close);
        }
        return ResponseEntity.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    /**
     * The reduced, trace-id-only per-request profile drill-down — see {@link RequestProfileAssembler}
     * for why Spring's fuller time-window/serving-thread heuristic tiers aren't ported to this reactive
     * stack (no request served on the event loop has a stable owning thread to correlate on).
     */
    @GetMapping("/request/{id}")
    public RequestProfileDto request(@PathVariable("id") String id) {
        HttpExchangesReport requests = requestsReport();
        HttpExchangeDto request = requests.exchanges().stream()
                .filter(exchange -> id.equals(exchange.id()))
                .findFirst()
                .orElse(null);
        String traceId = request == null ? null : request.traceId();
        TraceDetailDto trace = correlateTrace(traceId);
        boolean securityAvailable = properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS);

        return profileAssembler.profile(
                id,
                request,
                requests.exchanges(),
                sqlSnapshot().entries(),
                allExceptionDetails(),
                securityEvents(securityAvailable),
                trace);
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
        String healthStatus = currentHealthStatus();
        List<CapturedMessage> kafkaMessages = kafkaMessages();
        boolean kafkaAvailable = kafkaMessages != null;

        LiveActivityReport report = assembler.report(
                requests,
                sql.entries(),
                sql.available(),
                sql.unavailableWarning(),
                exceptionGroups(),
                securityEvents(securityAvailable),
                securityAvailable,
                healthStatus,
                limit,
                kafkaMessages,
                kafkaAvailable);

        // Adapter-side post-processing over the shared assembler's output, mirroring the Quarkus adapter
        // exactly: a REQUEST entry is profileable here iff its exchange carries a resolvable trace id,
        // since that is the exact (and only) signal #request can correlate on. This does not change the
        // engine's own `profileable` default (always false), nor the servlet controller's independent
        // computation.
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
     * Recent Kafka messages feeding the assembler's {@code MESSAGING} entries, or {@code null} when the
     * source is not feeding (Live Activity panel disabled, Kafka capture disabled via
     * {@code bootui.kafka.enabled}, or no recorder bean present) — same present-vs-absent distinction
     * {@link #sqlSnapshot()} and {@link #securityEvents(boolean)} make, so the assembler can tell "no
     * Kafka message yet" from "no Kafka source at all".
     */
    private List<CapturedMessage> kafkaMessages() {
        if (!properties.isPanelEnabled(BootUiPanels.ACTIVITY)) {
            return null;
        }
        KafkaActivityRecorder recorder = kafkaActivity.getIfAvailable();
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
