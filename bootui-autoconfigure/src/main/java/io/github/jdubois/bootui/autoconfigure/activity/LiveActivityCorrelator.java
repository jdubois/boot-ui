package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileExceptionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSecurityDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTimingDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Symfony-style per-request profiler. Given an HTTP exchange id, it joins the other in-memory
 * signals around that single request using a tiered strategy that degrades gracefully and never
 * fabricates data:
 *
 * <ol>
 *   <li>trace id (strongest): the distributed trace whose id matches the exchange;</li>
 *   <li>HTTP anchor: exceptions whose recorded request method + path match the exchange within its
 *       time window, further disambiguated by the serving thread when the request's thread is
 *       uniquely known (so a concurrent identical request cannot steal the occurrence);</li>
 *   <li>serving thread: SQL executions on the worker thread that handled the request, within its
 *       window — precise even without a trace id, because a servlet thread serves one request at a
 *       time. Falls back to a time-window heuristic (flagged approximate) when the request's thread
 *       cannot be uniquely identified;</li>
 *   <li>time window + principal: Spring Security audit events recorded inside the request window,
 *       restricted to the request's principal when both are known, and further pinned to the
 *       request's serving thread when {@link SecurityEventCorrelationRegistry} captured the event on
 *       it (so two concurrent requests sharing a principal cannot trade audit events). Audit events
 *       carry no trace id, so this stays heuristic only when the serving thread is unknown.</li>
 * </ol>
 */
public class LiveActivityCorrelator {

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceController> sqlTrace;
    private final ObjectProvider<ExceptionsController> exceptions;
    private final ObjectProvider<SecurityLogsController> securityLogs;
    private final ObjectProvider<TracesController> traces;
    private final ObjectProvider<RequestCorrelationRegistry> requestCorrelations;
    private final ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations;
    private final BootUiProperties properties;

    public LiveActivityCorrelator(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<RequestCorrelationRegistry> requestCorrelations,
            ObjectProvider<SecurityEventCorrelationRegistry> securityCorrelations,
            BootUiProperties properties) {
        this.httpExchanges = httpExchanges;
        this.sqlTrace = sqlTrace;
        this.exceptions = exceptions;
        this.securityLogs = securityLogs;
        this.traces = traces;
        this.requestCorrelations = requestCorrelations;
        this.securityCorrelations = securityCorrelations;
        this.properties = properties;
    }

    /** Build the profile for the request with the given HTTP exchange id. */
    public RequestProfileDto profile(String requestId) {
        HttpExchangeDto request = findRequest(requestId);
        if (request == null) {
            return RequestProfileDto.unavailable("Request " + requestId + " is no longer in the buffer");
        }

        long start = request.timestamp() == null ? 0L : request.timestamp().toEpochMilli();
        long end = request.durationMs() == null ? start : start + request.durationMs();
        // Resolve the serving thread once: a servlet request runs on a single worker thread, so the
        // same correlation sharpens SQL, exception, and security matching without re-scanning thrice.
        RequestCorrelation served = matchServingThread(request, start, end);

        List<String> notes = new ArrayList<>();

        SqlCorrelation sqlCorrelation = correlateSql(request, start, end, served);
        List<SqlTraceEntryDto> sql = sqlCorrelation.entries();
        boolean sqlApproximate = sqlCorrelation.approximate();
        if (!sql.isEmpty()) {
            if (sqlApproximate) {
                notes.add("SQL is correlated by time window only (no matching trace id or serving "
                        + "thread), so it may include or miss statements under concurrent requests.");
            } else if (sqlCorrelation.matchedBy() == SqlMatch.THREAD) {
                notes.add("SQL is correlated exactly by the request's serving thread within its window.");
            } else {
                notes.add("SQL is correlated exactly by trace id " + request.traceId() + ".");
            }
        }
        List<SqlTraceGroupDto> sqlGroups = groupSql(sql);

        ExceptionCorrelation exceptionCorrelation = correlateExceptions(request, start, end, served);
        List<RequestProfileExceptionDto> requestExceptions = exceptionCorrelation.entries();
        if (!requestExceptions.isEmpty()) {
            notes.add(
                    exceptionCorrelation.byThread()
                            ? "Exceptions are matched exactly by the request's serving thread, within its window and "
                                    + "alongside the request method and path."
                            : "Exceptions are matched by request method, path and time window.");
        }

        SecurityCorrelation securityCorrelation = correlateSecurity(request, start, end, served);
        List<RequestProfileSecurityDto> security = securityCorrelation.entries();
        if (!security.isEmpty()) {
            if (securityCorrelation.byThread()) {
                notes.add("Security events are matched exactly by the request's serving thread; events emitted on "
                        + "other threads (for example a concurrent request sharing the principal) are excluded.");
            } else {
                notes.add("Security events are matched by time window"
                        + (request.principal() == null
                                ? "."
                                : " and the request principal " + request.principal() + "."));
            }
        }

        TraceDetailDto trace = correlateTrace(request.traceId());
        if (trace != null) {
            notes.add("Trace matched by id " + request.traceId() + ".");
        }

        long sqlMs = sql.stream().mapToLong(SqlTraceEntryDto::durationMillis).sum();
        Double sqlPercent = (request.durationMs() != null && request.durationMs() > 0)
                ? Math.round(10000.0 * sqlMs / request.durationMs()) / 100.0
                : null;
        RequestProfileTimingDto timing =
                new RequestProfileTimingDto(request.durationMs(), sqlMs, sql.size(), sqlPercent);

        return new RequestProfileDto(
                true, null, request, sql, sqlGroups, sqlApproximate, requestExceptions, security, trace, timing, notes);
    }

    private HttpExchangeDto findRequest(String requestId) {
        if (requestId == null || !properties.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)) {
            return null;
        }
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return null;
        }
        HttpExchangesReport report = controller.exchanges(
                null, null, null, 0, properties.getActivity().getMaxEntries());
        if (report.unavailableReason() != null) {
            return null;
        }
        return report.exchanges().stream()
                .filter(exchange -> requestId.equals(exchange.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Correlate SQL to the request with a tiered strategy, strongest key first:
     *
     * <ol>
     *   <li><b>trace id</b> — an exact join when both the request and the captured statements carry a
     *       matching id (Micrometer tracing present and a trace context on the request);</li>
     *   <li><b>serving thread</b> — an exact join on the worker thread that handled the request,
     *       within its handling window. A servlet request runs on one thread which serves only one
     *       request at a time, so this is precise even without any trace id. Requires a unique
     *       {@link RequestCorrelation} for the request (see {@link RequestCorrelationRegistry#match});
     *   </li>
     *   <li><b>time window</b> — the heuristic fallback, flagged approximate, used only when neither
     *       exact key is available.</li>
     * </ol>
     *
     * The returned {@link SqlCorrelation#approximate()} flag drives the visible "approximate" badge.
     */
    private SqlCorrelation correlateSql(HttpExchangeDto request, long start, long end, RequestCorrelation served) {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return SqlCorrelation.empty();
        }
        SqlTraceController controller = sqlTrace.getIfAvailable();
        if (controller == null) {
            return SqlCorrelation.empty();
        }
        SqlTraceReport report = controller.trace();
        if (!report.available()) {
            return SqlCorrelation.empty();
        }

        String traceId = request.traceId();
        if (traceId != null && !traceId.isBlank()) {
            List<SqlTraceEntryDto> exact = new ArrayList<>();
            for (SqlTraceEntryDto entry : report.entries()) {
                if (traceId.equals(entry.traceId())) {
                    exact.add(entry);
                }
            }
            if (!exact.isEmpty()) {
                exact.sort(Comparator.comparingLong(SqlTraceEntryDto::timestamp));
                return new SqlCorrelation(exact, false, SqlMatch.TRACE_ID);
            }
        }

        if (served != null) {
            List<SqlTraceEntryDto> byThread = new ArrayList<>();
            for (SqlTraceEntryDto entry : report.entries()) {
                if (served.thread().equals(entry.thread())
                        && entry.timestamp() >= served.startMillis() - ActivitySql.WINDOW_SLACK_MS
                        && entry.timestamp() <= served.endMillis() + ActivitySql.WINDOW_SLACK_MS) {
                    byThread.add(entry);
                }
            }
            if (!byThread.isEmpty()) {
                byThread.sort(Comparator.comparingLong(SqlTraceEntryDto::timestamp));
                return new SqlCorrelation(byThread, false, SqlMatch.THREAD);
            }
        }

        List<SqlTraceEntryDto> matched = new ArrayList<>();
        for (SqlTraceEntryDto entry : report.entries()) {
            if (entry.timestamp() >= start && entry.timestamp() <= end) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator.comparingLong(SqlTraceEntryDto::timestamp));
        return new SqlCorrelation(matched, !matched.isEmpty(), SqlMatch.TIME_WINDOW);
    }

    private RequestCorrelation matchServingThread(HttpExchangeDto request, long start, long end) {
        RequestCorrelationRegistry registry = requestCorrelations == null ? null : requestCorrelations.getIfAvailable();
        if (registry == null) {
            return null;
        }
        return registry.match(request.method(), request.path(), start, end);
    }

    /** How the SQL tier matched the request; drives the human-readable note. */
    private enum SqlMatch {
        TRACE_ID,
        THREAD,
        TIME_WINDOW
    }

    /** The SQL statements correlated to a request, how they matched, and whether it was heuristic. */
    private record SqlCorrelation(List<SqlTraceEntryDto> entries, boolean approximate, SqlMatch matchedBy) {
        static SqlCorrelation empty() {
            return new SqlCorrelation(List.of(), false, SqlMatch.TIME_WINDOW);
        }
    }

    private List<SqlTraceGroupDto> groupSql(List<SqlTraceEntryDto> sql) {
        if (sql.isEmpty()) {
            return List.of();
        }
        int nPlusOneThreshold = properties.getActivity().getNPlusOneThreshold();
        Map<String, long[]> stats = new LinkedHashMap<>(); // [executions, totalDuration, maxDuration]
        Map<String, String> categories = new LinkedHashMap<>();
        for (SqlTraceEntryDto entry : sql) {
            String key = normalizeSql(entry.sql());
            long[] slot = stats.computeIfAbsent(key, k -> new long[3]);
            slot[0] += 1;
            slot[1] += entry.durationMillis();
            slot[2] = Math.max(slot[2], entry.durationMillis());
            categories.putIfAbsent(key, entry.category());
        }
        List<SqlTraceGroupDto> groups = new ArrayList<>();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            long executions = e.getValue()[0];
            String category = categories.getOrDefault(e.getKey(), "OTHER");
            boolean nPlusOne = "SELECT".equalsIgnoreCase(category) && executions >= nPlusOneThreshold;
            groups.add(
                    new SqlTraceGroupDto(e.getKey(), category, executions, e.getValue()[1], e.getValue()[2], nPlusOne));
        }
        groups.sort(Comparator.comparingLong(SqlTraceGroupDto::executions).reversed());
        return groups;
    }

    private ExceptionCorrelation correlateExceptions(
            HttpExchangeDto request, long start, long end, RequestCorrelation served) {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return ExceptionCorrelation.empty();
        }
        ExceptionsController controller = exceptions.getIfAvailable();
        if (controller == null) {
            return ExceptionCorrelation.empty();
        }
        ExceptionsReport report = controller.list();
        if (!report.available()) {
            return ExceptionCorrelation.empty();
        }
        String method = request.method();
        String path = request.path();

        // When the request's serving thread is uniquely known, use it to disambiguate occurrences
        // that share this method + path + window but were thrown while serving a different,
        // concurrent request. Web occurrences always carry the throwing thread, and log-sourced
        // occurrences (no request path) are already excluded by occurrenceMatches, so a non-null
        // serving thread lets us keep exactly the occurrences thrown on this request's thread.
        String servingThread = served == null ? null : served.thread();

        List<RequestProfileExceptionDto> matched = new ArrayList<>();
        for (ExceptionGroupDto group : report.groups()) {
            if (!pathMatches(group.lastRequestPath(), path)) {
                continue;
            }
            ExceptionDetailDto detail = safeDetail(controller, group.id());
            if (detail == null) {
                continue;
            }
            for (ExceptionOccurrenceDto occurrence : detail.occurrences()) {
                if (!occurrenceMatches(occurrence, method, path, start, end)) {
                    continue;
                }
                if (servingThread != null
                        && occurrence.thread() != null
                        && !servingThread.equals(occurrence.thread())) {
                    continue;
                }
                matched.add(new RequestProfileExceptionDto(
                        group.exceptionClassName(),
                        group.message(),
                        group.location(),
                        occurrence.timestamp(),
                        occurrence.thread(),
                        occurrence.handler(),
                        occurrence.source()));
            }
        }
        matched.sort(Comparator.comparingLong(RequestProfileExceptionDto::timestamp));
        return new ExceptionCorrelation(matched, servingThread != null && !matched.isEmpty());
    }

    /** Exceptions correlated to a request and whether the serving-thread tier disambiguated them. */
    private record ExceptionCorrelation(List<RequestProfileExceptionDto> entries, boolean byThread) {
        static ExceptionCorrelation empty() {
            return new ExceptionCorrelation(List.of(), false);
        }
    }

    private boolean occurrenceMatches(
            ExceptionOccurrenceDto occurrence, String method, String path, long start, long end) {
        if (!pathMatches(occurrence.requestPath(), path)) {
            return false;
        }
        if (method != null
                && occurrence.requestMethod() != null
                && !method.equalsIgnoreCase(occurrence.requestMethod())) {
            return false;
        }
        // Allow a small slack around the window for clock granularity between capture sources.
        return occurrence.timestamp() >= start - ActivitySql.WINDOW_SLACK_MS
                && occurrence.timestamp() <= end + ActivitySql.WINDOW_SLACK_MS;
    }

    /**
     * Correlate Spring Security audit events to the request. Audit events carry no trace id, so the
     * base join is heuristic: an event is kept when it was recorded inside the request window (with a
     * small slack for clock granularity) and, when both the request and the event name a principal,
     * only when those principals match. When the request's serving thread is uniquely known and
     * {@link SecurityEventCorrelationRegistry} captured the audit event, the match is sharpened: events
     * proven to have been emitted on another thread are dropped, and the remaining on-thread events are
     * flagged as exact. This is what links, for example, the {@code AUTHORIZATION_FAILURE} raised while
     * serving a secured endpoint to that very request, even under a concurrent request sharing the
     * principal.
     */
    private SecurityCorrelation correlateSecurity(
            HttpExchangeDto request, long start, long end, RequestCorrelation served) {
        if (!properties.isPanelEnabled(BootUiPanels.SECURITY_LOGS)) {
            return SecurityCorrelation.empty();
        }
        SecurityLogsController controller = securityLogs.getIfAvailable();
        if (controller == null) {
            return SecurityCorrelation.empty();
        }
        SecurityLogsReport report =
                controller.logs(null, null, null, 0, properties.getActivity().getMaxEntries());
        if (!report.auditEventsPresent()) {
            return SecurityCorrelation.empty();
        }
        String requestPrincipal = request.principal();
        SecurityEventCorrelationRegistry registry =
                securityCorrelations == null ? null : securityCorrelations.getIfAvailable();
        boolean byThread = false;
        List<RequestProfileSecurityDto> matched = new ArrayList<>();
        for (SecurityLogEventDto event : report.events()) {
            long timestamp = ActivitySql.parseEpochMillis(event.timestamp());
            if (timestamp < start - ActivitySql.WINDOW_SLACK_MS || timestamp > end + ActivitySql.WINDOW_SLACK_MS) {
                continue;
            }
            boolean principalMatched = principalMatches(requestPrincipal, event.principal());
            if (requestPrincipal != null && event.principal() != null && !principalMatched) {
                continue;
            }
            boolean threadMatched = false;
            if (served != null && registry != null) {
                SecurityEventCorrelationRegistry.ThreadMatch match = registry.classify(
                        served.thread(), event.type(), timestamp, ActivitySql.SECURITY_THREAD_SLACK_MS);
                if (match == SecurityEventCorrelationRegistry.ThreadMatch.FOREIGN) {
                    continue;
                }
                if (match == SecurityEventCorrelationRegistry.ThreadMatch.OURS) {
                    threadMatched = true;
                    byThread = true;
                }
            }
            matched.add(new RequestProfileSecurityDto(
                    event.type(), event.principal(), timestamp, principalMatched, threadMatched));
        }
        matched.sort(Comparator.comparingLong(RequestProfileSecurityDto::timestamp));
        return new SecurityCorrelation(matched, byThread);
    }

    /** The security audit events correlated to a request, and whether the exact serving-thread tier fired. */
    private record SecurityCorrelation(List<RequestProfileSecurityDto> entries, boolean byThread) {
        static SecurityCorrelation empty() {
            return new SecurityCorrelation(List.of(), false);
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

    private ExceptionDetailDto safeDetail(ExceptionsController controller, String id) {
        try {
            return controller.detail(id);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean pathMatches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private static boolean principalMatches(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String normalizeSql(String sql) {
        return ActivitySql.normalize(sql);
    }
}
