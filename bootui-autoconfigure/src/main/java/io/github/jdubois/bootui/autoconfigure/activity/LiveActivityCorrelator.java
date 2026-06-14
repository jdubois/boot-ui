package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileExceptionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTimingDto;
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
 *       time window;</li>
 *   <li>time window (heuristic): SQL executions that completed inside the request window. SQL carries
 *       no trace id, so this can over- or under-match under concurrency and is flagged approximate.</li>
 * </ol>
 */
public class LiveActivityCorrelator {

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceController> sqlTrace;
    private final ObjectProvider<ExceptionsController> exceptions;
    private final ObjectProvider<TracesController> traces;
    private final BootUiProperties properties;

    public LiveActivityCorrelator(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<TracesController> traces,
            BootUiProperties properties) {
        this.httpExchanges = httpExchanges;
        this.sqlTrace = sqlTrace;
        this.exceptions = exceptions;
        this.traces = traces;
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

        List<String> notes = new ArrayList<>();

        List<SqlTraceEntryDto> sql = correlateSql(start, end);
        boolean sqlApproximate = !sql.isEmpty();
        if (sqlApproximate) {
            notes.add("SQL is correlated by time window only (statements carry no trace id), so it may "
                    + "include or miss statements under concurrent requests.");
        }
        List<SqlTraceGroupDto> sqlGroups = groupSql(sql);

        List<RequestProfileExceptionDto> requestExceptions = correlateExceptions(request, start, end);
        if (!requestExceptions.isEmpty()) {
            notes.add("Exceptions are matched by request method, path and time window.");
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
                true, null, request, sql, sqlGroups, sqlApproximate, requestExceptions, trace, timing, notes);
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

    private List<SqlTraceEntryDto> correlateSql(long start, long end) {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return List.of();
        }
        SqlTraceController controller = sqlTrace.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        SqlTraceReport report = controller.trace();
        if (!report.available()) {
            return List.of();
        }
        List<SqlTraceEntryDto> matched = new ArrayList<>();
        for (SqlTraceEntryDto entry : report.entries()) {
            if (entry.timestamp() >= start && entry.timestamp() <= end) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator.comparingLong(SqlTraceEntryDto::timestamp));
        return matched;
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

    private List<RequestProfileExceptionDto> correlateExceptions(HttpExchangeDto request, long start, long end) {
        if (!properties.isPanelEnabled(BootUiPanels.EXCEPTIONS)) {
            return List.of();
        }
        ExceptionsController controller = exceptions.getIfAvailable();
        if (controller == null) {
            return List.of();
        }
        ExceptionsReport report = controller.list();
        if (!report.available()) {
            return List.of();
        }
        String method = request.method();
        String path = request.path();
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
                if (occurrenceMatches(occurrence, method, path, start, end)) {
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
        }
        matched.sort(Comparator.comparingLong(RequestProfileExceptionDto::timestamp));
        return matched;
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
        long slack = 50L;
        return occurrence.timestamp() >= start - slack && occurrence.timestamp() <= end + slack;
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

    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }
}
