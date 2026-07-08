package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
import io.github.jdubois.bootui.engine.web.SecurityActivityIds;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Assembles the Live Activity merged stream and KPI summary by reusing BootUI's existing signal
 * controllers. Each source is consumed through its own controller so masking, self-filtering and
 * buffer bounds are inherited unchanged; this service never re-reads raw buffers directly.
 */
public class LiveActivityService {

    /** Maximum characters of a SQL statement shown inline in a stream summary. */
    private static final int SQL_SUMMARY_LENGTH = 120;

    static final String TYPE_REQUEST = "REQUEST";
    static final String TYPE_SQL = "SQL";
    static final String TYPE_EXCEPTION = "EXCEPTION";
    static final String TYPE_SECURITY = "SECURITY";
    static final String TYPE_CACHE = "CACHE";

    static final String SEVERITY_OK = "OK";
    static final String SEVERITY_SLOW = "SLOW";
    static final String SEVERITY_WARN = "WARN";
    static final String SEVERITY_ERROR = "ERROR";

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceController> sqlTrace;
    private final ObjectProvider<ExceptionsController> exceptions;
    private final ObjectProvider<SecurityLogsController> securityLogs;
    private final ObjectProvider<HealthController> health;
    private final ObjectProvider<RequestCorrelationRegistry> requestCorrelations;
    private final ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations;
    private final ObjectProvider<CacheActivityRecorder> cacheActivity;
    private final BootUiProperties properties;

    public LiveActivityService(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<HealthController> health,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            BootUiProperties properties) {
        this(
                httpExchanges,
                sqlTrace,
                exceptions,
                securityLogs,
                health,
                requestCorrelations,
                securityCorrelations,
                null,
                properties);
    }

    public LiveActivityService(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<HealthController> health,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            BootUiProperties properties) {
        this.httpExchanges = httpExchanges;
        this.sqlTrace = sqlTrace;
        this.exceptions = exceptions;
        this.securityLogs = securityLogs;
        this.health = health;
        this.requestCorrelations = requestCorrelations;
        this.securityCorrelations = securityCorrelations;
        this.cacheActivity = cacheActivity;
        this.properties = properties;
    }

    /**
     * Build the merged stream and KPIs.
     *
     * @param typeFilter optional case-insensitive type to keep, or {@code null} for all types
     * @param severityFilter optional case-insensitive severity to keep, or {@code null} for all
     * @param since only entries strictly newer than this epoch-millis cursor are returned (0 for all)
     * @param limit maximum number of entries to return after filtering ({@code <= 0} for the configured max)
     */
    public LiveActivityReport report(String typeFilter, String severityFilter, long since, int limit) {
        int cap = effectiveLimit(limit);
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        HttpExchangesReport requests = loadRequests(sources, warnings);
        SqlTraceReport sql = loadSql(sources);
        ExceptionsReport exceptionsReport = loadExceptions(sources);
        SecurityLogsReport security = loadSecurity(sources);
        List<CacheActivityEvent> cache = loadCache(sources);

        List<RequestAnchor> anchors = buildAnchors(requests);
        Map<String, RequestAnchor> anchorsById = new HashMap<>();
        for (RequestAnchor anchor : anchors) {
            anchorsById.put(anchor.id(), anchor);
        }
        SecurityEventCorrelationRegistry securityRegistry =
                securityCorrelations == null ? null : securityCorrelations.getIfAvailable();

        List<ActivityEntryDto> all = new ArrayList<>();
        // Build SQL, exception and security entries first so we know which requests carry a correlated
        // security event (and the principal it ran as) before we build the request entries themselves.
        // Also index each SQL entry by its resolved parent request id, reusing the exact same
        // matchSqlParent(...) call already needed to build its ActivityEntryDto, so the REQUEST loop below
        // can flag N+1 suspicion per request using precisely the SQL BootUI attributes to it.
        Map<String, List<SqlTraceEntryDto>> sqlByRequestId = new HashMap<>();
        if (sql != null) {
            for (SqlTraceEntryDto entry : sql.entries()) {
                String parentId = matchSqlParent(entry, anchors);
                all.add(toSqlEntry(entry, parentId));
                if (parentId != null) {
                    sqlByRequestId
                            .computeIfAbsent(parentId, id -> new ArrayList<>())
                            .add(entry);
                }
            }
        }
        if (exceptionsReport != null) {
            for (ExceptionGroupDto group : exceptionsReport.groups()) {
                all.add(toExceptionEntry(group, matchExceptionParent(group, anchors)));
            }
        }
        Map<String, String> securedByRequest = new HashMap<>();
        if (security != null) {
            for (SecurityLogEventDto event : security.events()) {
                String parentId = matchSecurityParent(event, anchors, securityRegistry);
                String principal = event.principal();
                // Only a non-blank principal marks the request as authenticated; a correlated event
                // with no principal (e.g. an anonymous failure) must not flag a misleading lock icon.
                if (parentId != null && principal != null && !principal.isBlank()) {
                    securedByRequest.putIfAbsent(parentId, principal);
                }
                all.add(toSecurityEntry(event, parentId));
            }
        }
        if (cache != null) {
            for (CacheActivityEvent event : cache) {
                all.add(toCacheEntry(event, matchCacheParent(event, anchors)));
            }
        }
        if (requests != null) {
            int nPlusOneThreshold = properties.getActivity().getNPlusOneThreshold();
            for (HttpExchangeDto exchange : requests.exchanges()) {
                RequestAnchor anchor = anchorsById.get(exchange.id());
                boolean sqlNPlusOneSuspected = SqlTraceGrouping.anySuspectedNPlusOne(
                        sqlByRequestId.getOrDefault(exchange.id(), List.of()), nPlusOneThreshold);
                all.add(toRequestEntry(
                        exchange,
                        anchor == null ? null : anchor.thread(),
                        securedByRequest.get(exchange.id()),
                        sqlNPlusOneSuspected));
            }
        }

        all.sort(Comparator.comparingLong(ActivityEntryDto::timestamp).reversed());

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (ActivityEntryDto entry : all) {
            typeCounts.merge(entry.type(), 1, Integer::sum);
        }

        String normalizedType = blankToNull(typeFilter);
        String normalizedSeverity = blankToNull(severityFilter);
        List<ActivityEntryDto> visible = new ArrayList<>();
        for (ActivityEntryDto entry : all) {
            if (entry.timestamp() <= since) {
                continue;
            }
            if (normalizedType != null && !entry.type().equalsIgnoreCase(normalizedType)) {
                continue;
            }
            if (normalizedSeverity != null && !entry.severity().equalsIgnoreCase(normalizedSeverity)) {
                continue;
            }
            visible.add(entry);
            if (visible.size() >= cap) {
                break;
            }
        }

        ActivityKpiDto kpis = computeKpis(requests, sql, exceptionsReport, cache);
        boolean available = !sources.isEmpty();
        return new LiveActivityReport(available, visible, typeCounts, kpis, sources, warnings);
    }

    private HttpExchangesReport loadRequests(List<String> sources, List<String> warnings) {
        if (!properties.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)) {
            return null;
        }
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return null;
        }
        HttpExchangesReport report = controller.exchanges(null, null, null, 0, effectiveLimit(0));
        if (report.unavailableReason() != null) {
            warnings.add("Requests: " + report.unavailableReason());
            return null;
        }
        sources.add("HTTP Exchanges");
        return report;
    }

    private SqlTraceReport loadSql(List<String> sources) {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return null;
        }
        SqlTraceController controller = sqlTrace.getIfAvailable();
        if (controller == null) {
            return null;
        }
        SqlTraceReport report = controller.trace();
        if (!report.available()) {
            return null;
        }
        sources.add("SQL Trace");
        return report;
    }

    private ExceptionsReport loadExceptions(List<String> sources) {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return null;
        }
        ExceptionsController controller = exceptions.getIfAvailable();
        if (controller == null) {
            return null;
        }
        ExceptionsReport report = controller.list();
        if (!report.available()) {
            return null;
        }
        sources.add("Exceptions");
        return report;
    }

    private SecurityLogsReport loadSecurity(List<String> sources) {
        if (!properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS)) {
            return null;
        }
        SecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return null;
        }
        SecurityLogsReport report = controller.logs(null, null, null, 0, effectiveLimit(0));
        if (!report.auditEventsPresent()) {
            return null;
        }
        sources.add("Security Logs");
        return report;
    }

    private List<CacheActivityEvent> loadCache(List<String> sources) {
        if (!properties.isPanelEnabled(BootUiPanels.CACHE) || cacheActivity == null) {
            return null;
        }
        CacheActivityRecorder recorder = cacheActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return null;
        }
        List<CacheActivityEvent> events = recorder.recentEvents();
        if (events.isEmpty()) {
            return null;
        }
        sources.add("Cache");
        return events;
    }

    private ActivityEntryDto toRequestEntry(
            HttpExchangeDto exchange, String servingThread, String securedPrincipal, boolean sqlNPlusOneSuspected) {
        long timestamp =
                exchange.timestamp() == null ? 0L : exchange.timestamp().toEpochMilli();
        int status = exchange.status();
        Long durationMs = exchange.durationMs();
        String severity;
        if (status >= 500) {
            severity = SEVERITY_ERROR;
        } else if (status >= 400) {
            severity = SEVERITY_WARN;
        } else if (durationMs != null && durationMs >= requestSlowThresholdMs()) {
            severity = SEVERITY_SLOW;
        } else {
            severity = SEVERITY_OK;
        }
        String path = exchange.path() == null ? "" : exchange.path();
        String summary = (exchange.method() == null ? "" : exchange.method() + " ") + path + " → " + status;
        return new ActivityEntryDto(
                exchange.id(),
                TYPE_REQUEST,
                timestamp,
                severity,
                summary.trim(),
                exchange.principal() == null ? null : "as " + exchange.principal(),
                durationMs,
                exchange.traceId(),
                exchange.method(),
                exchange.path(),
                status,
                servingThread,
                true,
                null,
                securedPrincipal,
                sqlNPlusOneSuspected);
    }

    private ActivityEntryDto toSqlEntry(SqlTraceEntryDto entry, String parentId) {
        String severity;
        if (!entry.success()) {
            severity = SEVERITY_ERROR;
        } else if (entry.slow()) {
            severity = SEVERITY_SLOW;
        } else {
            severity = SEVERITY_OK;
        }
        String sql = entry.sql() == null ? "" : entry.sql();
        String summary = truncate(ActivitySql.summarize(entry.category(), ActivitySql.normalize(sql)));
        String detail = entry.success() ? null : entry.errorMessage();
        return new ActivityEntryDto(
                "sql-" + entry.id(),
                TYPE_SQL,
                entry.timestamp(),
                severity,
                summary.trim(),
                detail,
                entry.durationMillis(),
                entry.traceId(),
                null,
                null,
                null,
                entry.thread(),
                false,
                parentId,
                null,
                false);
    }

    private ActivityEntryDto toExceptionEntry(ExceptionGroupDto group, String parentId) {
        String message = group.message() == null ? "" : ": " + group.message();
        String summary = group.exceptionClassName() + message;
        String detail = group.location();
        if (group.count() > 1) {
            detail = (detail == null ? "" : detail + " ") + "×" + group.count();
        }
        return new ActivityEntryDto(
                "exc-" + group.id(),
                TYPE_EXCEPTION,
                group.lastSeen(),
                SEVERITY_ERROR,
                summary.trim(),
                detail,
                null,
                null,
                group.lastRequestMethod(),
                group.lastRequestPath(),
                null,
                group.lastThread(),
                false,
                parentId,
                null,
                false);
    }

    private ActivityEntryDto toSecurityEntry(SecurityLogEventDto event, String parentId) {
        long timestamp = ActivitySql.parseEpochMillis(event.timestamp());
        String type = event.type() == null ? "" : event.type();
        String upper = type.toUpperCase(Locale.ROOT);
        String severity = upper.contains("FAILURE") || upper.contains("DENIED") ? SEVERITY_WARN : SEVERITY_OK;
        String principal = event.principal() == null ? "" : " · " + event.principal();
        return new ActivityEntryDto(
                SecurityActivityIds.stableId(event),
                TYPE_SECURITY,
                timestamp,
                severity,
                (type + principal).trim(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                parentId,
                null,
                false);
    }

    private ActivityEntryDto toCacheEntry(CacheActivityEvent event, String parentId) {
        String severity = event.operation() == CacheActivityOperation.MISS ? SEVERITY_WARN : SEVERITY_OK;
        String summary = event.operation().name() + " " + event.cacheName();
        String detail = event.keyHash() == null ? null : "key " + event.keyHash();
        return new ActivityEntryDto(
                "cache-" + event.seq(),
                TYPE_CACHE,
                event.timestampMillis(),
                severity,
                summary,
                detail,
                null,
                event.traceId(),
                null,
                null,
                null,
                event.thread(),
                false,
                parentId,
                null,
                false);
    }

    /**
     * Builds one {@link RequestAnchor} per recent HTTP request, resolving its serving thread and precise
     * window from {@link RequestCorrelationRegistry} when available. Used to attach correlated SQL,
     * exception, and security entries to the request that caused them.
     */
    private List<RequestAnchor> buildAnchors(HttpExchangesReport requests) {
        if (requests == null) {
            return List.of();
        }
        RequestCorrelationRegistry registry = requestCorrelations == null ? null : requestCorrelations.getIfAvailable();
        List<RequestAnchor> anchors = new ArrayList<>();
        for (HttpExchangeDto exchange : requests.exchanges()) {
            long start =
                    exchange.timestamp() == null ? 0L : exchange.timestamp().toEpochMilli();
            long end = exchange.durationMs() == null ? start : start + exchange.durationMs();
            String thread = null;
            if (registry != null && exchange.method() != null && exchange.path() != null) {
                RequestCorrelationRegistry.RequestCorrelation corr =
                        registry.match(exchange.method(), exchange.path(), start, end);
                if (corr != null) {
                    thread = corr.thread();
                    start = corr.startMillis();
                    end = corr.endMillis();
                }
            }
            anchors.add(new RequestAnchor(
                    exchange.id(), start, end, thread, exchange.traceId(), exchange.method(), exchange.path()));
        }
        return anchors;
    }

    /**
     * Resolves the request that a SQL statement belongs to: trace-id join first (exact), then serving
     * thread within the request window (exact). Returns {@code null} when neither tier yields a unique
     * request, so the entry stays top-level rather than being mis-attributed.
     */
    private static String matchSqlParent(SqlTraceEntryDto entry, List<RequestAnchor> anchors) {
        return matchByTraceThenThread(entry.traceId(), entry.thread(), entry.timestamp(), anchors);
    }

    /**
     * Resolves the request that a cache access belongs to using the same trace-id-then-serving-thread
     * tiering {@link #matchSqlParent} uses for SQL, since cache accesses are captured on the same
     * application thread as the request that triggered them.
     */
    private static String matchCacheParent(CacheActivityEvent event, List<RequestAnchor> anchors) {
        return matchByTraceThenThread(event.traceId(), event.thread(), event.timestampMillis(), anchors);
    }

    private static String matchByTraceThenThread(
            String traceId, String thread, long timestamp, List<RequestAnchor> anchors) {
        if (traceId != null && !traceId.isBlank()) {
            String byTrace = uniqueByTrace(anchors, traceId);
            if (byTrace != null) {
                return byTrace;
            }
        }
        if (thread == null) {
            return null;
        }
        String found = null;
        for (RequestAnchor anchor : anchors) {
            if (thread.equals(anchor.thread()) && covers(anchor, timestamp)) {
                if (found != null) {
                    return null;
                }
                found = anchor.id();
            }
        }
        return found;
    }

    /**
     * Resolves the request that an exception group belongs to by matching the last request method/path
     * within the request window, disambiguating by serving thread when more than one request matches.
     */
    private static String matchExceptionParent(ExceptionGroupDto group, List<RequestAnchor> anchors) {
        String method = group.lastRequestMethod();
        String path = group.lastRequestPath();
        if (method == null || path == null) {
            return null;
        }
        long ts = group.lastSeen();
        List<RequestAnchor> candidates = new ArrayList<>();
        for (RequestAnchor anchor : anchors) {
            if (method.equalsIgnoreCase(anchor.method())
                    && path.equalsIgnoreCase(anchor.path())
                    && covers(anchor, ts)) {
                candidates.add(anchor);
            }
        }
        if (candidates.size() > 1 && group.lastThread() != null) {
            candidates.removeIf(anchor -> !group.lastThread().equals(anchor.thread()));
        }
        return candidates.size() == 1 ? candidates.get(0).id() : null;
    }

    /**
     * Resolves the request that a security audit event belongs to using the serving-thread classifier:
     * the event is attributed to a request only when it was emitted on that request's serving thread.
     */
    private static String matchSecurityParent(
            SecurityLogEventDto event, List<RequestAnchor> anchors, SecurityEventCorrelationRegistry registry) {
        if (registry == null) {
            return null;
        }
        long ts = ActivitySql.parseEpochMillis(event.timestamp());
        String type = event.type();
        String found = null;
        for (RequestAnchor anchor : anchors) {
            if (anchor.thread() == null || !covers(anchor, ts)) {
                continue;
            }
            if (registry.classify(anchor.thread(), type, ts, ActivitySql.SECURITY_THREAD_SLACK_MS)
                    == SecurityEventCorrelationRegistry.ThreadMatch.OURS) {
                if (found != null) {
                    return null;
                }
                found = anchor.id();
            }
        }
        return found;
    }

    private static String uniqueByTrace(List<RequestAnchor> anchors, String traceId) {
        String found = null;
        for (RequestAnchor anchor : anchors) {
            if (traceId.equals(anchor.traceId())) {
                if (found != null) {
                    return null;
                }
                found = anchor.id();
            }
        }
        return found;
    }

    private static boolean covers(RequestAnchor anchor, long timestamp) {
        return timestamp >= anchor.start() - ActivitySql.WINDOW_SLACK_MS
                && timestamp <= anchor.end() + ActivitySql.WINDOW_SLACK_MS;
    }

    /**
     * A recent HTTP request reduced to what is needed to attach correlated signals to it: its id, the
     * (possibly thread-refined) time window, the serving thread when known, and the trace id.
     */
    private record RequestAnchor(
            String id, long start, long end, String thread, String traceId, String method, String path) {}

    private ActivityKpiDto computeKpis(
            HttpExchangesReport requests,
            SqlTraceReport sql,
            ExceptionsReport exceptions,
            List<CacheActivityEvent> cache) {
        double requestsPerMinute = 0;
        double errorRate = 0;
        Long p50 = null;
        Long p95 = null;
        String slowestEndpoint = null;
        Long slowestEndpointMs = null;
        if (requests != null && !requests.exchanges().isEmpty()) {
            List<HttpExchangeDto> list = requests.exchanges();
            requestsPerMinute = perMinute(list.stream()
                    .map(HttpExchangeDto::timestamp)
                    .filter(Objects::nonNull)
                    .map(Instant::toEpochMilli)
                    .toList());
            long errors = list.stream().filter(e -> e.status() >= 400).count();
            errorRate = 100.0 * errors / list.size();
            List<Long> durations = list.stream()
                    .map(HttpExchangeDto::durationMs)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            p50 = percentile(durations, 50);
            p95 = percentile(durations, 95);
            HttpExchangeDto slowest = list.stream()
                    .filter(e -> e.durationMs() != null)
                    .max(Comparator.comparingLong(HttpExchangeDto::durationMs))
                    .orElse(null);
            if (slowest != null) {
                slowestEndpoint = slowest.path();
                slowestEndpointMs = slowest.durationMs();
            }
        }

        double sqlPerMinute = 0;
        Long slowestQueryMs = null;
        if (sql != null && !sql.entries().isEmpty()) {
            sqlPerMinute = perMinute(
                    sql.entries().stream().map(SqlTraceEntryDto::timestamp).toList());
            slowestQueryMs = sql.entries().stream()
                    .mapToLong(SqlTraceEntryDto::durationMillis)
                    .max()
                    .orElse(0L);
        }

        int activeExceptions = exceptions == null ? 0 : exceptions.groups().size();
        String healthStatus = currentHealthStatus();

        Double cacheHitRatioPercent = null;
        if (cache != null && !cache.isEmpty()) {
            long hits = cache.stream()
                    .filter(e -> e.operation() == CacheActivityOperation.HIT)
                    .count();
            long misses = cache.stream()
                    .filter(e -> e.operation() == CacheActivityOperation.MISS)
                    .count();
            long reads = hits + misses;
            if (reads > 0) {
                cacheHitRatioPercent = round(100.0 * hits / reads);
            }
        }

        Long heapUsed = null;
        Long heapMax = null;
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            heapUsed = heap.getUsed();
            heapMax = heap.getMax() < 0 ? null : heap.getMax();
        } catch (RuntimeException ignored) {
            // Heap metrics are best-effort.
        }

        return new ActivityKpiDto(
                round(requestsPerMinute),
                round(errorRate),
                p50,
                p95,
                slowestEndpoint,
                slowestEndpointMs,
                activeExceptions,
                round(sqlPerMinute),
                slowestQueryMs,
                healthStatus,
                heapUsed,
                heapMax,
                cacheHitRatioPercent);
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
        double minutes = spanMs / 60_000.0;
        return timestamps.size() / Math.max(minutes, 1.0 / 60);
    }

    private static Long percentile(List<Long> sortedAscending, int percentile) {
        if (sortedAscending.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedAscending.size()) - 1;
        index = Math.max(0, Math.min(index, sortedAscending.size() - 1));
        return sortedAscending.get(index);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private long requestSlowThresholdMs() {
        return properties.getActivity().getRequestSlowThresholdMs();
    }

    private int effectiveLimit(int requested) {
        int max = properties.getActivity().getMaxEntries();
        if (requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    private static String truncate(String value) {
        if (value.length() <= SQL_SUMMARY_LENGTH) {
            return value;
        }
        return value.substring(0, SQL_SUMMARY_LENGTH) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
