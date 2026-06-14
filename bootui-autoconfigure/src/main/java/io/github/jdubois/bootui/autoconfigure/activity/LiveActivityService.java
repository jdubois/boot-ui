package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
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
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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

    static final String SEVERITY_OK = "OK";
    static final String SEVERITY_SLOW = "SLOW";
    static final String SEVERITY_WARN = "WARN";
    static final String SEVERITY_ERROR = "ERROR";

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<SqlTraceController> sqlTrace;
    private final ObjectProvider<ExceptionsController> exceptions;
    private final ObjectProvider<SecurityLogsController> securityLogs;
    private final ObjectProvider<HealthController> health;
    private final BootUiProperties properties;

    public LiveActivityService(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<HealthController> health,
            BootUiProperties properties) {
        this.httpExchanges = httpExchanges;
        this.sqlTrace = sqlTrace;
        this.exceptions = exceptions;
        this.securityLogs = securityLogs;
        this.health = health;
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

        List<ActivityEntryDto> all = new ArrayList<>();
        if (requests != null) {
            for (HttpExchangeDto exchange : requests.exchanges()) {
                all.add(toRequestEntry(exchange));
            }
        }
        if (sql != null) {
            for (SqlTraceEntryDto entry : sql.entries()) {
                all.add(toSqlEntry(entry));
            }
        }
        if (exceptionsReport != null) {
            for (ExceptionGroupDto group : exceptionsReport.groups()) {
                all.add(toExceptionEntry(group));
            }
        }
        if (security != null) {
            int index = 0;
            for (SecurityLogEventDto event : security.events()) {
                all.add(toSecurityEntry(event, index++));
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

        ActivityKpiDto kpis = computeKpis(requests, sql, exceptionsReport);
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

    private ActivityEntryDto toRequestEntry(HttpExchangeDto exchange) {
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
                null,
                true);
    }

    private ActivityEntryDto toSqlEntry(SqlTraceEntryDto entry) {
        String severity;
        if (!entry.success()) {
            severity = SEVERITY_ERROR;
        } else if (entry.slow()) {
            severity = SEVERITY_SLOW;
        } else {
            severity = SEVERITY_OK;
        }
        String sql = entry.sql() == null ? "" : entry.sql();
        String summary = entry.category() + " " + truncate(ActivitySql.normalize(sql));
        String detail = entry.success() ? null : entry.errorMessage();
        return new ActivityEntryDto(
                "sql-" + entry.id(),
                TYPE_SQL,
                entry.timestamp(),
                severity,
                summary.trim(),
                detail,
                entry.durationMillis(),
                null,
                null,
                null,
                null,
                entry.thread(),
                false);
    }

    private ActivityEntryDto toExceptionEntry(ExceptionGroupDto group) {
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
                false);
    }

    private ActivityEntryDto toSecurityEntry(SecurityLogEventDto event, int index) {
        long timestamp = parseEpochMillis(event.timestamp());
        String type = event.type() == null ? "" : event.type();
        String upper = type.toUpperCase(Locale.ROOT);
        String severity = upper.contains("FAILURE") || upper.contains("DENIED") ? SEVERITY_WARN : SEVERITY_OK;
        String principal = event.principal() == null ? "" : " · " + event.principal();
        return new ActivityEntryDto(
                "sec-" + index,
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
                false);
    }

    private ActivityKpiDto computeKpis(HttpExchangesReport requests, SqlTraceReport sql, ExceptionsReport exceptions) {
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
                heapMax);
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

    private static long parseEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0L;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
