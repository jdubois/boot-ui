package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.engine.support.Percentiles;
import io.github.jdubois.bootui.engine.web.RequestLatencyKpis;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Renders Live Activity's feed and KPIs from the runtime journal's retained events ({@code docs/PLAN-v2.md} §5.3), the
 * same way on Spring MVC, Spring WebFlux, and Quarkus.
 *
 * <p>Every child nests by identity, never by thread or time: an event carrying a request id nests under that request's
 * {@code REQUEST} entry, and one carrying only an execution id under the {@code SCHEDULED} or consumed
 * {@code MESSAGING} entry of that execution. An {@code AI} call, which carries only the trace id of the span it came
 * from, nests under the one retained request with that trace id whose time span contains the call's start
 * ({@link AiCallOwners}). A child whose parent is not retained, such as a request still in flight, stays top-level
 * until its parent appears.</p>
 *
 * <p>Entries carry only what the journal records: templates, statements as retained, types, and classes, never bind
 * values, principals, or exception and log messages (§8). A {@code REQUEST} entry's id is its request id, which the
 * profile drill-down resolves; a scheduled run's or consumed message's id is its execution id; every other entry's id
 * is its journal event id. Logical connections and garbage
 * collections are not feed rows: they appear in the request profile and the resource views.</p>
 */
public final class JournalActivityFeed {

    public static final String TYPE_REQUEST = "REQUEST";
    public static final String TYPE_SQL = "SQL";
    public static final String TYPE_REST_CLIENT = "REST_CLIENT";
    public static final String TYPE_EXCEPTION = "EXCEPTION";
    public static final String TYPE_SECURITY = "SECURITY";
    public static final String TYPE_CACHE = "CACHE";
    public static final String TYPE_SCHEDULED = "SCHEDULED";
    public static final String TYPE_MESSAGING = "MESSAGING";
    public static final String TYPE_TRANSACTION = "TRANSACTION";
    public static final String TYPE_LOG = "LOG";
    public static final String TYPE_MAIL = "MAIL";
    public static final String TYPE_FAULT_TOLERANCE = "FAULT_TOLERANCE";
    public static final String TYPE_AI = "AI";
    public static final String TYPE_MARKER = "MARKER";
    public static final String TYPE_APP_EVENT = "APP_EVENT";
    public static final String TYPE_WEBSOCKET = "WEBSOCKET";
    public static final String TYPE_ORM = "ORM";
    public static final String TYPE_ASYNC = "ASYNC";

    static final String SEVERITY_OK = "OK";
    static final String SEVERITY_SLOW = "SLOW";
    static final String SEVERITY_WARN = "WARN";
    static final String SEVERITY_ERROR = "ERROR";

    /** Maximum characters of a statement or log template shown in a summary. */
    static final int MAX_SUMMARY = 160;

    static final int MAX_SELECTS_PER_REQUEST = 16;
    static final int MAX_SELECT_FINGERPRINT_LENGTH = 512;

    private final long requestSlowThresholdMs;
    private final int nPlusOneThreshold;
    private final Supplier<RouteTemplateResolver> declaredRoutes;
    private final Supplier<JournalTextExposure> exposure;

    /**
     * @param requestSlowThresholdMs {@code bootui.activity.request-slow-threshold-ms}; {@code 0} flags nothing slow
     * @param nPlusOneThreshold executions of one {@code SELECT} within a request at which it is flagged as an N+1
     * @param declaredRoutes the application's declared routes, which name a request's route when the framework recorded
     *     no template, as on Quarkus; {@code null} for none
     */
    public JournalActivityFeed(
            long requestSlowThresholdMs, int nPlusOneThreshold, Supplier<RouteTemplateResolver> declaredRoutes) {
        this(requestSlowThresholdMs, nPlusOneThreshold, declaredRoutes, null);
    }

    /**
     * A feed whose rows show SQL, log text, and request paths as the live {@code exposure} policy allows when each
     * render is read ({@code PLAN-v2} §8).
     *
     * @param exposure the live exposure policy; {@code null} renders as {@link JournalTextExposure#masked()}
     */
    public JournalActivityFeed(
            long requestSlowThresholdMs,
            int nPlusOneThreshold,
            Supplier<RouteTemplateResolver> declaredRoutes,
            ExposurePolicy exposure) {
        this.requestSlowThresholdMs = requestSlowThresholdMs;
        this.nPlusOneThreshold = nPlusOneThreshold;
        this.declaredRoutes = declaredRoutes == null ? RouteTemplateResolver::empty : declaredRoutes;
        this.exposure = exposure == null ? JournalTextExposure::masked : () -> JournalTextExposure.of(exposure);
    }

    /** The exposure rule the live policy prescribes right now, resolved once per read. */
    public JournalTextExposure exposure() {
        return exposure.get();
    }

    /**
     * Renders {@code entries}, the journal's retained events, into feed entries, newest first, keeping the first
     * {@code limit} that pass {@code filter}.
     *
     * @param eventId the journal's stable id of an entry, {@link RuntimeJournal#eventId}
     * @param runId the run the entries belong to, which a run filter must name
     */
    public Feed render(
            List<JournalEntry> entries,
            Function<JournalEntry, String> eventId,
            String runId,
            Filter filter,
            int limit) {
        return render(entries, eventId, runId, filter, limit, JournalRowDetails.NONE);
    }

    /**
     * Renders as {@link #render(List, Function, String, Filter, int)} does, completing each row with the masked detail
     * {@code details} still holds (D27), for the live feed only.
     */
    public Feed render(
            List<JournalEntry> entries,
            Function<JournalEntry, String> eventId,
            String runId,
            Filter filter,
            int limit,
            JournalRowDetails details) {
        return render(entries, eventId, runId, filter, limit, details, traceId -> false);
    }

    /**
     * Renders as {@link #render(List, Function, String, Filter, int, JournalRowDetails)} does, with
     * {@code evictedRequestTraces} naming the traces of requests the journal evicted, whose AI calls linked only by
     * trace nest under no request ({@link AiCallOwners}).
     */
    public Feed render(
            List<JournalEntry> entries,
            Function<JournalEntry, String> eventId,
            String runId,
            Filter filter,
            int limit,
            JournalRowDetails details,
            Predicate<String> evictedRequestTraces) {
        return render(entries, eventId, runId, filter, limit, details, evictedRequestTraces, List.of());
    }

    /**
     * Renders as {@link #render(List, Function, String, Filter, int, JournalRowDetails, Predicate)} does, adding an
     * {@link #TYPE_ASYNC} entry, badged {@link ActivityEntryDto#BADGE_RUNNING}, for each task the BootUI agent propagated
     * that is still running for a request or execution the feed shows (M5-2).
     */
    public Feed render(
            List<JournalEntry> entries,
            Function<JournalEntry, String> eventId,
            String runId,
            Filter filter,
            int limit,
            JournalRowDetails details,
            Predicate<String> evictedRequestTraces,
            List<RunningHandoffs.Running> running) {
        return render(entries, eventId, runId, filter, limit, details, evictedRequestTraces, running, exposure());
    }

    /**
     * Renders as {@link #render(List, Function, String, Filter, int, JournalRowDetails, Predicate, List)} does, under
     * {@code text}, an exposure rule the caller resolved once for its whole response.
     */
    public Feed render(
            List<JournalEntry> entries,
            Function<JournalEntry, String> eventId,
            String runId,
            Filter filter,
            int limit,
            JournalRowDetails details,
            Predicate<String> evictedRequestTraces,
            List<RunningHandoffs.Running> running,
            JournalTextExposure text) {
        JournalTextExposure rule = text == null ? JournalTextExposure.masked() : text;
        RouteTemplateResolver routes = resolver();
        JournalRowDetails rowDetails = details == null ? JournalRowDetails.NONE : details;
        Map<String, JournalEntry> requests = new HashMap<>();
        Map<String, String> executions = new HashMap<>();
        Map<String, Map<String, Integer>> selectsByRequest = new HashMap<>();
        AiCallOwners aiCallOwners = new AiCallOwners(evictedRequestTraces);
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (event.source() == JournalSource.HTTP && event.requestId() != null) {
                requests.put(event.requestId(), entry);
                aiCallOwners.learn(event);
            } else if (opensExecution(event)) {
                executions.putIfAbsent(event.executionId(), event.executionId());
            }
            if (event.payload() instanceof SqlPayload sql && event.requestId() != null && isSelect(sql.sql())) {
                countSelect(selectsByRequest.computeIfAbsent(event.requestId(), id -> new HashMap<>()), sql.sql());
            }
        }

        List<Row> rows = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            ActivityEntryDto rendered =
                    render(entry, eventId, requests, executions, aiCallOwners, selectsByRequest, routes, false, rule);
            if (rendered != null) {
                rows.add(
                        new Row(entry, rowDetails.apply(rendered, entry.event()), aiCallOwners.ownerOf(entry.event())));
            }
        }
        long syntheticSequence = Long.MAX_VALUE;
        for (RunningHandoffs.Running handoff : running == null ? List.<RunningHandoffs.Running>of() : running) {
            String parentId = handoff.requestId() != null && requests.containsKey(handoff.requestId())
                    ? handoff.requestId()
                    : handoff.requestId() == null && executions.containsKey(handoff.executionId())
                            ? handoff.executionId()
                            : null;
            if (parentId == null) {
                // Its request has not completed yet, or belongs to another application: nothing to nest it under.
                continue;
            }
            RuntimeEvent event = new RuntimeEvent(
                    JournalSource.AGENT_EXECUTORS,
                    handoff.startEpochMillis(),
                    -1,
                    handoff.requestId(),
                    handoff.executionId(),
                    handoff.traceId(),
                    handoff.thread(),
                    null,
                    false,
                    null);
            List<String> badges = new ArrayList<>(2);
            badges.add(ActivityEntryDto.BADGE_RUNNING);
            JournalEntry request = handoff.requestId() == null ? null : requests.get(handoff.requestId());
            if (request != null) {
                badges.add(ActivityEntryDto.BADGE_AFTER_RESPONSE);
            }
            ActivityEntryDto entry = new ActivityEntryDto(
                    // The handoffs of a scheduled run or consumed message share its execution id; the registry's key
                    // tells them apart.
                    "running:" + (handoff.id() == RunningHandoffs.NONE ? handoff.executionId() : handoff.id()),
                    TYPE_ASYNC,
                    handoff.startEpochMillis(),
                    SEVERITY_WARN,
                    asyncSummary(handoff.taskClass()),
                    "still running · " + (handoff.hook() == null ? "executor" : handoff.hook()),
                    null,
                    handoff.traceId(),
                    null,
                    null,
                    null,
                    handoff.thread(),
                    false,
                    parentId,
                    null,
                    false,
                    badges);
            rows.add(new Row(new JournalEntry(syntheticSequence--, event, 0), entry, handoff.requestId()));
        }
        rows.sort(Comparator.comparingLong((Row row) -> row.entry().timestamp())
                .thenComparingLong(row -> row.journal().sequence())
                .reversed());

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (Row row : rows) {
            typeCounts.merge(row.entry().type(), 1, Integer::sum);
        }
        Filter applied = filter == null ? Filter.NONE : filter;
        if (applied.runId() != null && !applied.runId().equals(runId)) {
            return new Feed(List.of(), typeCounts);
        }
        Set<String> routeRequests = applied.route() == null ? null : requestsOnRoute(requests, applied.route(), routes);
        int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<ActivityEntryDto> visible = new ArrayList<>();
        for (Row row : rows) {
            if (visible.size() >= cap) {
                break;
            }
            if (applied.accepts(row, routeRequests)) {
                visible.add(row.entry());
            }
        }
        return new Feed(visible, typeCounts);
    }

    /**
     * The KPI strip over {@code entries}, the journal's retained events, computed as 1.x computes it over its buffers.
     *
     * @param healthStatus the application's health status, or {@code null}
     */
    public ActivityKpiDto kpis(List<JournalEntry> entries, String healthStatus) {
        return kpis(entries, healthStatus, exposure());
    }

    /** The KPI strip as {@link #kpis(List, String)} computes it, its slowest path shown under {@code text}. */
    public ActivityKpiDto kpis(List<JournalEntry> entries, String healthStatus, JournalTextExposure text) {
        JournalTextExposure rule = text == null ? JournalTextExposure.masked() : text;
        RouteTemplateResolver routes = resolver();
        List<HttpExchangeDto> exchanges = new ArrayList<>();
        List<Long> requestTimes = new ArrayList<>();
        long requestErrors = 0;
        List<Long> sqlTimes = new ArrayList<>();
        Long slowestQueryMs = null;
        List<Long> restDurations = new ArrayList<>();
        long restErrors = 0;
        Set<String> exceptionGroups = new HashSet<>();
        long cacheHits = 0;
        long cacheMisses = 0;
        int scheduledFailures = 0;
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            RuntimeEventPayload payload = event.payload();
            if (payload instanceof HttpPayload http) {
                requestTimes.add(event.epochMillis());
                if (http.status() >= 400) {
                    requestErrors++;
                }
                RouteLabel label = routeOf(http, routes);
                exchanges.add(new HttpExchangeDto(
                        event.requestId(),
                        Instant.ofEpochMilli(event.epochMillis()),
                        http.method(),
                        rule.path(http.path()),
                        null,
                        null,
                        http.status(),
                        null,
                        millis(event),
                        null,
                        null,
                        null,
                        null,
                        event.traceId(),
                        List.of(),
                        List.of(),
                        label.route(),
                        label.source().name(),
                        event.requestId()));
            } else if (payload instanceof SqlPayload) {
                sqlTimes.add(event.epochMillis());
                long ms = millis(event);
                slowestQueryMs = slowestQueryMs == null ? ms : Math.max(slowestQueryMs, ms);
            } else if (payload instanceof RestClientPayload rest) {
                restDurations.add(millis(event));
                if (rest.failed() || (rest.status() != null && rest.status() >= 400)) {
                    restErrors++;
                }
            } else if (payload instanceof ExceptionPayload exception && exception.groupId() != null) {
                exceptionGroups.add(exception.groupId());
            } else if (payload instanceof CachePayload cache) {
                if ("HIT".equalsIgnoreCase(cache.operation())) {
                    cacheHits++;
                } else if ("MISS".equalsIgnoreCase(cache.operation())) {
                    cacheMisses++;
                }
            } else if (payload instanceof ScheduledPayload scheduled && scheduled.exceptionClass() != null) {
                scheduledFailures++;
            }
        }
        RequestLatencyKpis latency = RequestLatencyKpis.of(exchanges);
        Long heapUsed = null;
        Long heapMax = null;
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            heapUsed = heap.getUsed();
            heapMax = heap.getMax() < 0 ? null : heap.getMax();
        } catch (RuntimeException ignored) {
            // Heap metrics are best-effort.
        }
        long cacheReads = cacheHits + cacheMisses;
        return new ActivityKpiDto(
                round(perMinute(requestTimes)),
                exchanges.isEmpty() ? 0 : round(100.0 * requestErrors / exchanges.size()),
                latency.p50Ms(),
                latency.p95Ms(),
                latency.slowestPath(),
                latency.slowestMs(),
                exceptionGroups.size(),
                round(perMinute(sqlTimes)),
                slowestQueryMs,
                healthStatus,
                heapUsed,
                heapMax,
                cacheReads == 0 ? null : round(100.0 * cacheHits / cacheReads),
                scheduledFailures,
                restDurations.isEmpty() ? null : round(100.0 * restErrors / restDurations.size()),
                restDurations.isEmpty() ? null : Percentiles.of(restDurations, 95),
                latency.sampleCount(),
                latency.slowestRoute(),
                latency.slowestRouteId(),
                latency.slowestRouteSource());
    }

    private ActivityEntryDto render(
            JournalEntry journal,
            Function<JournalEntry, String> eventId,
            Map<String, JournalEntry> requests,
            Map<String, String> executions,
            AiCallOwners aiCallOwners,
            Map<String, Map<String, Integer>> selectsByRequest,
            RouteTemplateResolver routes,
            boolean byIdentity,
            JournalTextExposure text) {
        RuntimeEvent event = journal.event();
        RuntimeEventPayload payload = event.payload();
        // A scheduled run or consumed message is identified by its execution id, as a request is by its request id,
        // so its children can name it before it is recorded.
        String id = opensExecution(event) ? event.executionId() : eventId.apply(journal);
        String parentId = parentOf(event, requests, executions, aiCallOwners, byIdentity);
        Long durationMs = millis(event);
        if (payload instanceof HttpPayload http) {
            Map<String, Integer> selects = selectsByRequest.get(event.requestId());
            boolean nPlusOne =
                    selects != null && selects.values().stream().anyMatch(count -> count >= nPlusOneThreshold);
            String maskedPath = text.path(http.path());
            String path = maskedPath == null ? "" : maskedPath;
            String summary = (http.method() == null ? "" : http.method() + " ") + path + " → " + http.status();
            return entry(
                    event.requestId() == null ? id : event.requestId(),
                    TYPE_REQUEST,
                    event,
                    requestSeverity(http.status(), durationMs),
                    summary.trim(),
                    routeDetail(http, routes),
                    durationMs,
                    http.method(),
                    maskedPath,
                    http.status(),
                    event.requestId() != null,
                    null,
                    nPlusOne);
        }
        if (payload instanceof SqlPayload sql) {
            String severity = sql.failed() ? SEVERITY_ERROR : event.failedOrSlow() ? SEVERITY_SLOW : SEVERITY_OK;
            return entry(
                    id,
                    TYPE_SQL,
                    event,
                    severity,
                    truncate(text.sql(sql.sql())),
                    sql.dataSource(),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof RestClientPayload rest) {
            String severity;
            if (rest.failed() || (rest.status() != null && rest.status() >= 500)) {
                severity = SEVERITY_ERROR;
            } else if (rest.status() != null && rest.status() >= 400) {
                severity = SEVERITY_WARN;
            } else if (event.failedOrSlow()) {
                severity = SEVERITY_SLOW;
            } else {
                severity = SEVERITY_OK;
            }
            String outcome = rest.failed() || rest.status() == null ? "failed" : String.valueOf(rest.status());
            String restPath = text.path(rest.path());
            String summary = (rest.method() == null ? "" : rest.method() + " ")
                    + (rest.authority() == null ? "" : rest.authority())
                    + (restPath == null ? "" : restPath)
                    + " → " + outcome;
            return entry(
                    id,
                    TYPE_REST_CLIENT,
                    event,
                    severity,
                    summary.trim(),
                    rest.clientType(),
                    durationMs,
                    rest.method(),
                    restPath,
                    rest.status(),
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof ExceptionPayload exception) {
            // As 1.x does, an exception names the request it was raised in.
            JournalEntry owner = event.requestId() == null ? null : requests.get(event.requestId());
            HttpPayload ownerHttp =
                    owner == null ? null : (HttpPayload) owner.event().payload();
            return entry(
                            id,
                            TYPE_EXCEPTION,
                            event,
                            SEVERITY_ERROR,
                            exception.exceptionClass() == null ? "Exception" : exception.exceptionClass(),
                            null,
                            null,
                            ownerHttp == null ? null : ownerHttp.method(),
                            ownerHttp == null ? null : text.path(ownerHttp.path()),
                            null,
                            false,
                            parentId,
                            false)
                    .withExceptionGroupId(exception.groupId());
        }
        if (payload instanceof SecurityPayload security) {
            String type = security.type() == null ? "" : security.type();
            return entry(
                    id,
                    TYPE_SECURITY,
                    event,
                    SecurityPayload.isFailure(type) ? SEVERITY_WARN : SEVERITY_OK,
                    type,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof CachePayload cache) {
            String operation = cache.operation() == null ? "" : cache.operation();
            return entry(
                    id,
                    TYPE_CACHE,
                    event,
                    "MISS".equalsIgnoreCase(operation) ? SEVERITY_WARN : SEVERITY_OK,
                    (operation + " " + (cache.cacheName() == null ? "" : cache.cacheName())).trim(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof ScheduledPayload scheduled) {
            String severity = scheduled.exceptionClass() != null
                    ? SEVERITY_ERROR
                    : RequestSlowThreshold.isSlow(durationMs, requestSlowThresholdMs) ? SEVERITY_SLOW : SEVERITY_OK;
            return entry(
                    id,
                    TYPE_SCHEDULED,
                    event,
                    severity,
                    scheduled.task() == null ? "Scheduled task" : scheduled.task(),
                    scheduled.exceptionClass(),
                    durationMs,
                    null,
                    null,
                    null,
                    opensExecution(event),
                    parentId,
                    false);
        }
        if (payload instanceof AsyncHandoffPayload handoff) {
            List<String> badges = new ArrayList<>(2);
            JournalEntry request = event.requestId() == null ? null : requests.get(event.requestId());
            boolean afterResponse = handoff.afterResponse() != null
                    ? handoff.afterResponse()
                    : request != null
                            && event.epochMillis() + Math.max(0, event.durationNanos()) / 1_000_000L
                                    > request.event().epochMillis()
                                            + Math.max(0, request.event().durationNanos()) / 1_000_000L;
            if (afterResponse) {
                badges.add(ActivityEntryDto.BADGE_AFTER_RESPONSE);
            }
            if (handoff.capped()) {
                badges.add(ActivityEntryDto.BADGE_CAPPED);
            }
            StringBuilder detail = new StringBuilder(handoff.hook() == null ? "executor" : handoff.hook());
            detail.append(" · queued ")
                    .append(Math.max(0, handoff.queuedNanos()) / 1_000_000L)
                    .append(" ms");
            if (handoff.exceptionClass() != null) {
                detail.append(" · ").append(simpleName(handoff.exceptionClass()));
            }
            return new ActivityEntryDto(
                    id,
                    TYPE_ASYNC,
                    event.epochMillis(),
                    handoff.failed() ? SEVERITY_ERROR : handoff.capped() ? SEVERITY_WARN : SEVERITY_OK,
                    asyncSummary(handoff.taskClass()),
                    detail.toString(),
                    durationMs,
                    event.traceId(),
                    null,
                    null,
                    null,
                    event.thread(),
                    false,
                    parentId,
                    null,
                    false,
                    badges);
        }
        if (payload instanceof MessagingPayload message) {
            String summary =
                    (message.sent() ? "→ " : "← ") + (message.destination() == null ? "" : message.destination());
            return entry(
                    id,
                    TYPE_MESSAGING,
                    event,
                    message.failed() ? SEVERITY_ERROR : SEVERITY_OK,
                    summary.trim(),
                    message.broker(),
                    message.sent() ? null : durationMs,
                    null,
                    null,
                    null,
                    !message.sent() && event.requestId() == null && event.executionId() != null,
                    parentId,
                    false);
        }
        if (payload instanceof TransactionPayload transaction) {
            return entry(
                    id,
                    TYPE_TRANSACTION,
                    event,
                    transaction.rolledBack() ? SEVERITY_WARN : SEVERITY_OK,
                    transaction.method() == null ? "Transaction" : transaction.method(),
                    transactionDetail(transaction),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof MailPayload mail) {
            // The journal keeps no subject or address (§8), so the row names what it can count.
            String summary = "Email to " + mail.recipients() + (mail.recipients() == 1 ? " recipient" : " recipients");
            String detail = mail.attachments() == 0
                    ? null
                    : mail.attachments() + (mail.attachments() == 1 ? " attachment" : " attachments");
            if (!mail.sent()) {
                detail = (detail == null ? "" : detail + " · ") + "dev-trap: not sent";
            }
            return entry(
                    id,
                    TYPE_MAIL,
                    event,
                    mail.sent() ? SEVERITY_OK : SEVERITY_WARN,
                    summary,
                    detail,
                    null,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof FaultTolerancePayload ft) {
            StringBuilder summary = new StringBuilder(ft.outcome() == null ? "EVENT" : ft.outcome())
                    .append(' ')
                    .append(ft.policy());
            if (ft.policyType() != null && !ft.policyType().isBlank()) {
                summary.append(" (")
                        .append(ft.policyType().toLowerCase(Locale.ROOT).replace('_', ' '))
                        .append(')');
            }
            List<String> details = new ArrayList<>();
            if (ft.target() != null && !ft.target().isBlank()) {
                details.add(ft.target());
            }
            if (ft.attempt() != null) {
                details.add("attempt " + ft.attempt());
            }
            if (ft.state() != null && !ft.state().isBlank()) {
                details.add("state " + ft.state());
            }
            if (ft.failureCategory() != null && !ft.failureCategory().isBlank()) {
                details.add(ft.failureCategory());
            }
            return entry(
                    id,
                    TYPE_FAULT_TOLERANCE,
                    event,
                    ft.failure() ? SEVERITY_ERROR : ft.protective() ? SEVERITY_WARN : SEVERITY_OK,
                    summary.toString(),
                    details.isEmpty() ? null : String.join(" · ", details),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof AiPayload ai) {
            return entry(
                    id,
                    TYPE_AI,
                    event,
                    ai.failed() ? SEVERITY_ERROR : ai.lengthLimited() ? SEVERITY_WARN : SEVERITY_OK,
                    aiSummary(ai),
                    aiDetail(ai),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof OrmPayload orm) {
            return entry(
                    id,
                    TYPE_ORM,
                    event,
                    orm.partialFlushes() >= 3 ? SEVERITY_WARN : SEVERITY_OK,
                    "Hibernate session" + (orm.persistenceUnit() == null ? "" : " " + orm.persistenceUnit()),
                    ormDetail(orm),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof WebSocketPayload webSocket) {
            String summary = (webSocket.inbound() ? "← " : "→ ")
                    + (webSocket.destination() == null ? webSocket.kind() : webSocket.destination());
            return entry(
                    id,
                    TYPE_WEBSOCKET,
                    event,
                    webSocket.failed()
                            ? SEVERITY_ERROR
                            : RequestSlowThreshold.isSlow(durationMs, requestSlowThresholdMs)
                                    ? SEVERITY_SLOW
                                    : SEVERITY_OK,
                    summary,
                    webSocket.endpoint() == null ? "websocket" : "websocket " + webSocket.endpoint(),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof AppEventPayload appEvent) {
            boolean published = AppEventPayload.PUBLISHED.equals(appEvent.kind());
            return entry(
                    id,
                    TYPE_APP_EVENT,
                    event,
                    AppEventPayload.FAILED.equals(appEvent.outcome())
                            ? SEVERITY_ERROR
                            : AppEventPayload.SKIPPED_NO_TRANSACTION.equals(appEvent.outcome())
                                    ? SEVERITY_WARN
                                    : SEVERITY_OK,
                    published
                            ? simpleName(appEvent.eventType()) + " published"
                            : (appEvent.listener() == null ? "listener" : appEvent.listener()),
                    published ? appEvent.listeners() + " listeners" : appEventDetail(appEvent),
                    durationMs,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        if (payload instanceof LifecyclePayload lifecycle && lifecycle.marker()) {
            return entry(
                    id,
                    TYPE_MARKER,
                    event,
                    SEVERITY_OK,
                    markerSummary(lifecycle),
                    lifecycle.target(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    false);
        }
        if (payload instanceof LogPayload log) {
            String level = log.level() == null ? "" : log.level().toUpperCase(Locale.ROOT);
            String detail = log.logger();
            if (log.exceptionClass() != null) {
                detail = (detail == null ? "" : detail + " · ") + log.exceptionClass();
            }
            return entry(
                    id,
                    TYPE_LOG,
                    event,
                    "ERROR".equals(level) ? SEVERITY_ERROR : SEVERITY_WARN,
                    truncate(Objects.requireNonNullElse(text.message(log.template()), "")),
                    detail,
                    null,
                    null,
                    null,
                    null,
                    false,
                    parentId,
                    false);
        }
        return null;
    }

    private ActivityEntryDto entry(
            String id,
            String type,
            RuntimeEvent event,
            String severity,
            String summary,
            String detail,
            Long durationMs,
            String method,
            String path,
            Integer status,
            boolean profileable,
            String parentId,
            boolean sqlNPlusOneSuspected) {
        return new ActivityEntryDto(
                id,
                type,
                event.epochMillis(),
                severity,
                summary,
                detail,
                durationMs,
                event.traceId(),
                method,
                path,
                status,
                event.thread(),
                profileable,
                parentId,
                null,
                sqlNPlusOneSuspected);
    }

    /** An AI call's summary: its operation, model, and provider, such as {@code chat gpt-4o (openai)}. */
    /**
     * A transaction's outcome and declared attributes, such as {@code rolled back (rollback-only) · read-only ·
     * REQUIRES_NEW · REPEATABLE_READ · UnexpectedRollbackException}.
     */
    static String transactionDetail(TransactionPayload transaction) {
        StringBuilder detail = new StringBuilder(transaction.rolledBack() ? "rolled back" : "committed");
        if (transaction.rollbackOnly()) {
            detail.append(" (rollback-only)");
        }
        if (transaction.readOnly()) {
            detail.append(" · read-only");
        }
        if (!"REQUIRED".equals(transaction.propagation())) {
            detail.append(" · ").append(transaction.propagation());
        }
        if (transaction.isolation() != null) {
            detail.append(" · ").append(transaction.isolation());
        }
        if (transaction.failureClass() != null) {
            int dot = transaction.failureClass().lastIndexOf('.');
            detail.append(" · ").append(transaction.failureClass().substring(dot + 1));
        }
        return detail.toString();
    }

    /** A Hibernate session row's detail, such as {@code 12 statements · 3 flushes (2 auto) · 540 entities}. */
    static String ormDetail(OrmPayload orm) {
        StringBuilder detail = new StringBuilder()
                .append(orm.statements())
                .append(orm.statements() == 1 ? " statement" : " statements");
        int flushes = orm.flushes() + orm.partialFlushes();
        if (flushes > 0) {
            detail.append(" · ").append(flushes).append(flushes == 1 ? " flush" : " flushes");
            if (orm.partialFlushes() > 0) {
                detail.append(" (").append(orm.partialFlushes()).append(" auto)");
            }
        }
        if (orm.entitiesInContext() >= 0) {
            detail.append(" · ").append(orm.entitiesInContext()).append(" entities");
        }
        return detail.toString();
    }

    /** A listener row's detail, such as {@code OrderPlaced · AFTER_COMMIT · skipped, no transaction}. */
    static String appEventDetail(AppEventPayload event) {
        String outcome =
                switch (event.outcome() == null ? "" : event.outcome()) {
                    case AppEventPayload.SKIPPED_NO_TRANSACTION -> "skipped, no transaction";
                    case AppEventPayload.DEFERRED -> "deferred";
                    case AppEventPayload.FAILED ->
                        "failed" + (event.exceptionClass() == null ? "" : ": " + simpleName(event.exceptionClass()));
                    default -> "ran";
                };
        return simpleName(event.eventType()) + " · " + (event.phase() == null ? "IMMEDIATE" : event.phase()) + " · "
                + outcome;
    }

    /** An {@code ASYNC} entry's summary: the task's simple class name, such as {@code Async task OrderMailer}. */
    static String asyncSummary(String taskClass) {
        return "Async task" + (taskClass == null ? "" : " " + simpleName(taskClass));
    }

    private static String simpleName(String type) {
        if (type == null) {
            return "event";
        }
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    /** What a marker says on the time axis, such as {@code BootUI action} for a change made from a panel. */
    public static String markerSummary(LifecyclePayload marker) {
        return switch (marker.kind()) {
            case LifecyclePayload.ACTION -> "BootUI action";
            case LifecyclePayload.AVAILABILITY -> "Availability changed";
            case LifecyclePayload.CONFIG_REFRESH -> "Configuration refreshed";
            case LifecyclePayload.SHUTDOWN -> "Application shutting down";
            default -> marker.kind();
        };
    }

    private static String aiSummary(AiPayload ai) {
        StringBuilder summary = new StringBuilder(ai.operation() == null ? "AI call" : ai.operation());
        if (ai.model() != null && !ai.model().isBlank()) {
            summary.append(' ').append(ai.model());
        }
        if (ai.provider() != null && !ai.provider().isBlank()) {
            summary.append(" (").append(ai.provider()).append(')');
        }
        return summary.toString();
    }

    /** An AI call's tokens and finish reason, or {@code null} when it reported neither. */
    private static String aiDetail(AiPayload ai) {
        List<String> details = new ArrayList<>(3);
        if (ai.inputTokens() != null) {
            details.add(ai.inputTokens() + " input tokens");
        }
        if (ai.outputTokens() != null) {
            details.add(ai.outputTokens() + " output tokens");
        }
        if (ai.finishReason() != null && !ai.finishReason().isBlank()) {
            details.add("finish " + ai.finishReason());
        }
        return details.isEmpty() ? null : String.join(" · ", details);
    }

    /**
     * The id of the entry {@code event} nests under: its request's, else its execution's, else, for an AI call, the
     * request {@code aiCallOwners} attributes it to, else none. The live feed names only a parent it shows;
     * {@code byIdentity} names it whether or not it was recorded yet, for rows persisted as they are recorded.
     */
    private static String parentOf(
            RuntimeEvent event,
            Map<String, JournalEntry> requests,
            Map<String, String> executions,
            AiCallOwners aiCallOwners,
            boolean byIdentity) {
        if (event.source() == JournalSource.HTTP || opensExecution(event)) {
            return null;
        }
        if (event.requestId() != null) {
            return byIdentity || requests.containsKey(event.requestId()) ? event.requestId() : null;
        }
        if (event.executionId() != null) {
            return byIdentity || executions.containsKey(event.executionId()) ? event.executionId() : null;
        }
        String owner = aiCallOwners.ownerOf(event);
        return owner != null && (byIdentity || requests.containsKey(owner)) ? owner : null;
    }

    /**
     * Renders one batch of newly recorded events for persistence, newest first ({@code docs/PLAN-v2.md} §5.3). Each row
     * names its parent by identity, since a request or execution is recorded after its children. An AI call linked
     * to its request only by trace and time is written on its own, since a request recorded later could change that
     * inference and a written row is never revised. {@code pendingSelects} carries each open request's {@code SELECT}
     * counts from batch to batch, so its N+1 flag is set when it completes; the caller bounds it.
     */
    public List<ActivityEntryDto> renderForCapture(
            List<JournalEntry> batch,
            Function<JournalEntry, String> eventId,
            Map<String, Map<String, Integer>> pendingSelects) {
        return renderForCapture(batch, eventId, pendingSelects, count -> {});
    }

    public List<ActivityEntryDto> renderForCapture(
            List<JournalEntry> batch,
            Function<JournalEntry, String> eventId,
            Map<String, Map<String, Integer>> pendingSelects,
            LongConsumer overflow) {
        // Learns no request, so it infers no parent from a trace.
        AiCallOwners aiCallOwners = new AiCallOwners();
        RouteTemplateResolver routes = resolver();
        for (JournalEntry entry : batch) {
            RuntimeEvent event = entry.event();
            if (event.payload() instanceof SqlPayload sql && event.requestId() != null && isSelect(sql.sql())) {
                if (!countSelect(pendingSelects.computeIfAbsent(event.requestId(), id -> new HashMap<>()), sql.sql())) {
                    overflow.accept(1);
                }
            }
        }
        List<Row> rows = new ArrayList<>(batch.size());
        for (JournalEntry entry : batch) {
            ActivityEntryDto rendered = render(
                    entry,
                    eventId,
                    Map.of(),
                    Map.of(),
                    aiCallOwners,
                    pendingSelects,
                    routes,
                    true,
                    JournalTextExposure.masked());
            if (rendered != null) {
                rows.add(new Row(entry, rendered, aiCallOwners.ownerOf(entry.event())));
            }
            if (entry.event().source() == JournalSource.HTTP && entry.event().requestId() != null) {
                pendingSelects.remove(entry.event().requestId());
            }
        }
        rows.sort(Comparator.comparingLong((Row row) -> row.entry().timestamp())
                .thenComparingLong(row -> row.journal().sequence())
                .reversed());
        return rows.stream().map(Row::entry).toList();
    }

    /** Whether {@code event} is the entry of an execution: a scheduled run or a consumed message. */
    private static boolean opensExecution(RuntimeEvent event) {
        if (event.executionId() == null || event.requestId() != null) {
            return false;
        }
        return event.payload() instanceof ScheduledPayload
                || (event.payload() instanceof MessagingPayload message && !message.sent())
                || (event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution());
    }

    private String requestSeverity(int status, Long durationMs) {
        if (status >= 500) {
            return SEVERITY_ERROR;
        }
        if (status >= 400) {
            return SEVERITY_WARN;
        }
        return RequestSlowThreshold.isSlow(durationMs, requestSlowThresholdMs) ? SEVERITY_SLOW : SEVERITY_OK;
    }

    /** The route a request was grouped under, when it is a template rather than its own path. */
    private static String routeDetail(HttpPayload http, RouteTemplateResolver routes) {
        RouteLabel label = routeOf(http, routes);
        return label.source().isTemplate()
                        && label.route() != null
                        && !label.route().equals(http.path())
                ? label.route()
                : null;
    }

    private static RouteLabel routeOf(HttpPayload http, RouteTemplateResolver routes) {
        return RouteLabel.of(http.method(), http.path(), http.routeTemplate(), http.operation(), routes);
    }

    private static Set<String> requestsOnRoute(
            Map<String, JournalEntry> requests, String route, RouteTemplateResolver routes) {
        Set<String> matching = new HashSet<>();
        requests.forEach((requestId, entry) -> {
            RouteLabel label = routeOf((HttpPayload) entry.event().payload(), routes);
            if (route.equals(label.id()) || route.equals(label.route())) {
                matching.add(requestId);
            }
        });
        return matching;
    }

    private RouteTemplateResolver resolver() {
        try {
            RouteTemplateResolver resolver = declaredRoutes.get();
            return resolver == null ? RouteTemplateResolver.empty() : resolver;
        } catch (RuntimeException ex) {
            return RouteTemplateResolver.empty();
        }
    }

    private static boolean isSelect(String sql) {
        return sql != null && sql.stripLeading().regionMatches(true, 0, "select", 0, 6);
    }

    /** Returns false when a fingerprint replaces another at the per-request cap. */
    static boolean countSelect(Map<String, Integer> selects, String sql) {
        String fingerprint = SqlShapes.fingerprint(sql);
        if (fingerprint.length() > MAX_SELECT_FINGERPRINT_LENGTH) {
            try {
                fingerprint = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is required for bounded SELECT tracking", ex);
            }
        }
        boolean overflow = false;
        if (!selects.containsKey(fingerprint) && selects.size() >= MAX_SELECTS_PER_REQUEST) {
            String leastFrequent = selects.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .orElseThrow()
                    .getKey();
            selects.remove(leastFrequent);
            overflow = true;
        }
        selects.merge(fingerprint, 1, Integer::sum);
        return !overflow;
    }

    private static Long millis(RuntimeEvent event) {
        return event.durationNanos() < 0 ? null : event.durationNanos() / 1_000_000L;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_SUMMARY ? value : value.substring(0, MAX_SUMMARY) + "…";
    }

    private static double perMinute(List<Long> timestamps) {
        if (timestamps.size() < 2) {
            return timestamps.size();
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long timestamp : timestamps) {
            min = Math.min(min, timestamp);
            max = Math.max(max, timestamp);
        }
        long spanMs = max - min;
        if (spanMs <= 0) {
            return timestamps.size();
        }
        return timestamps.size() / Math.max(spanMs / 60_000.0, 1.0 / 60);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** A rendered entry with the request it belongs to, by its own request id or, for an AI call, its time and trace. */
    private record Row(JournalEntry journal, ActivityEntryDto entry, String requestId) {}

    /** The rendered feed and its per-type counts over every rendered entry, before filtering. */
    public record Feed(List<ActivityEntryDto> entries, Map<String, Integer> typeCounts) {

        public Feed {
            entries = List.copyOf(entries);
            typeCounts = Map.copyOf(typeCounts);
        }
    }

    /**
     * Which entries the feed shows. Every field is optional.
     *
     * @param type an entry type, such as {@code SQL}
     * @param severity a severity, such as {@code ERROR}
     * @param since only entries strictly after this epoch millisecond
     * @param route a route, as {@code METHOD /template} or {@code /template}: its requests and their children
     * @param runId a run id; another run than the journal's shows nothing
     * @param requestId one request and its children
     * @param noRequest only work outside any request: executions, their children, and work on no request's thread
     */
    public record Filter(
            String type, String severity, long since, String route, String runId, String requestId, boolean noRequest) {

        public static final Filter NONE = new Filter(null, null, 0, null, null, null, false);

        public Filter {
            type = BlankStrings.blankToNullTrimmed(type);
            severity = BlankStrings.blankToNullTrimmed(severity);
            route = BlankStrings.blankToNullTrimmed(route);
            runId = BlankStrings.blankToNullTrimmed(runId);
            requestId = BlankStrings.blankToNullTrimmed(requestId);
        }

        boolean accepts(Row row, Set<String> routeRequests) {
            ActivityEntryDto entry = row.entry();
            String request = row.requestId();
            if (entry.timestamp() <= since) {
                return false;
            }
            if (type != null && !entry.type().equalsIgnoreCase(type)) {
                return false;
            }
            if (severity != null && !entry.severity().equalsIgnoreCase(severity)) {
                return false;
            }
            if (requestId != null && !requestId.equals(request)) {
                return false;
            }
            if (routeRequests != null && (request == null || !routeRequests.contains(request))) {
                return false;
            }
            return !noRequest || request == null;
        }
    }
}
