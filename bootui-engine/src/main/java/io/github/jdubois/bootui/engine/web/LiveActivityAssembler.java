package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral assembly of the Live Activity merged stream + KPI summary from already-masked source
 * reports. The Spring adapter has its own richer, controller-fed service (with per-request signal
 * correlation and thread attribution); the Quarkus adapter feeds this assembler the three signal sources it
 * captures — HTTP requests, SQL trace, and exceptions — which are merged into one reverse-chronological feed
 * with JVM heap and per-type KPIs.
 *
 * <p>Inputs are already masked and self-filtered by their owning engine services before they reach this
 * shape, so the assembler only normalizes, severities, merges, correlates and caps.</p>
 *
 * <p><strong>Correlation is data-driven on the shared trace id.</strong> Spring's thread-per-request model
 * lets it attribute signals by serving thread, but on the Vert.x event loop a thread does not map to a
 * single request, so that strategy is unportable. Instead, when the adapter stamps a distributed-trace id on
 * each signal (the Quarkus adapter reads {@code Span.current()} when OpenTelemetry is present, whose context
 * propagates across the event-loop→worker hops), this assembler nests each SQL/exception entry under the
 * REQUEST entry sharing the same trace id by setting its {@code parentId}. A child is only nested when
 * <em>exactly one</em> request carries that trace id (a uniqueness guard against a reused inbound
 * {@code traceparent}). When no trace id is stamped — OpenTelemetry absent — every {@code correlationId} is
 * {@code null}, no {@code parentId} is set, and the feed renders flat (the honest status quo). {@code
 * profileable} stays {@code false} regardless: the per-request profile drill-down is a Spring-only endpoint,
 * and nesting needs only {@code parentId}.</p>
 */
public final class LiveActivityAssembler {

    private static final long SLOW_MS = 500L;

    /** Maximum characters of a SQL statement shown inline in a stream summary. */
    private static final int MAX_SQL_SUMMARY = 160;

    private static final String TYPE_REQUEST = "REQUEST";
    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_EXCEPTION = "EXCEPTION";

    private static final String SEVERITY_OK = "OK";
    private static final String SEVERITY_SLOW = "SLOW";
    private static final String SEVERITY_WARN = "WARN";
    private static final String SEVERITY_ERROR = "ERROR";

    /**
     * Builds the report by merging the captured signals.
     *
     * @param requests already-masked HTTP exchanges (newest-first), or {@code null}
     * @param sqlEntries already-masked SQL trace executions (newest-first), or {@code null}; ignored unless
     *     {@code sqlAvailable}
     * @param sqlAvailable whether the SQL trace source is present and feeding (a datasource is configured)
     * @param sqlUnavailableWarning adapter-supplied explanation surfaced when {@code !sqlAvailable} (e.g. no
     *     datasource configured vs tracing intentionally disabled), or {@code null}/blank to surface none
     * @param exceptionGroups captured exception groups (newest-first), or {@code null}
     * @param healthStatus current health status, or {@code null}
     * @param limit maximum merged entries to return, or {@code 0}/negative for no cap
     */
    public LiveActivityReport report(
            HttpExchangesReport requests,
            List<SqlTraceEntryDto> sqlEntries,
            boolean sqlAvailable,
            String sqlUnavailableWarning,
            List<ExceptionGroupDto> exceptionGroups,
            String healthStatus,
            int limit) {

        List<HttpExchangeDto> exchanges = requests == null ? List.of() : requests.exchanges();
        List<SqlTraceEntryDto> sql = !sqlAvailable || sqlEntries == null ? List.of() : sqlEntries;
        List<ExceptionGroupDto> exceptions = exceptionGroups == null ? List.of() : exceptionGroups;

        List<ActivityEntryDto> entries = new ArrayList<>();

        long errors = 0;
        long slowest = 0;
        String slowestPath = null;
        List<Long> durations = new ArrayList<>();

        // Map each non-blank request trace id to its REQUEST entry id, tracking trace ids shared by more
        // than one request so an ambiguous (reused) trace never nests a child under the wrong request.
        Map<String, String> requestIdByTrace = new HashMap<>();
        Set<String> ambiguousTraces = new HashSet<>();

        for (HttpExchangeDto e : exchanges) {
            long ts = e.timestamp() == null ? 0L : e.timestamp().toEpochMilli();
            if (e.status() >= 400) {
                errors++;
            }
            if (e.durationMs() != null) {
                durations.add(e.durationMs());
                if (e.durationMs() > slowest) {
                    slowest = e.durationMs();
                    slowestPath = e.path();
                }
            }
            String trace = blankToNull(e.traceId());
            if (trace != null && requestIdByTrace.putIfAbsent(trace, e.id()) != null) {
                ambiguousTraces.add(trace);
            }
            entries.add(new ActivityEntryDto(
                    e.id(),
                    TYPE_REQUEST,
                    ts,
                    requestSeverity(e.status(), e.durationMs()),
                    (e.method() == null ? "" : e.method() + " ") + (e.path() == null ? "" : e.path()),
                    e.status() + (e.durationMs() == null ? "" : " · " + e.durationMs() + "ms"),
                    e.durationMs(),
                    e.traceId(),
                    e.method(),
                    e.path(),
                    e.status(),
                    null,
                    false,
                    null,
                    e.principal()));
        }

        Long slowestQuery = null;
        for (SqlTraceEntryDto s : sql) {
            if (slowestQuery == null || s.durationMillis() > slowestQuery) {
                slowestQuery = s.durationMillis();
            }
            entries.add(toSqlEntry(s, parentFor(s.traceId(), requestIdByTrace, ambiguousTraces)));
        }

        for (ExceptionGroupDto g : exceptions) {
            entries.add(toExceptionEntry(g, parentFor(g.lastTraceId(), requestIdByTrace, ambiguousTraces)));
        }

        entries.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (ActivityEntryDto entry : entries) {
            typeCounts.merge(entry.type(), 1, Integer::sum);
        }

        List<String> sources = new ArrayList<>();
        sources.add("requests");
        sources.add("exceptions");
        if (sqlAvailable) {
            sources.add("sql");
        }

        List<String> warnings = new ArrayList<>();
        if (!sqlAvailable && sqlUnavailableWarning != null && !sqlUnavailableWarning.isBlank()) {
            warnings.add(sqlUnavailableWarning);
        }

        ActivityKpiDto kpis = new ActivityKpiDto(
                0d,
                exchanges.isEmpty() ? 0d : (errors * 100d) / exchanges.size(),
                percentile(durations, 50),
                percentile(durations, 95),
                slowestPath,
                slowest == 0 ? null : slowest,
                exceptions.size(),
                0d,
                slowestQuery,
                healthStatus,
                heapUsed(),
                heapMax());
        return new LiveActivityReport(true, entries, typeCounts, kpis, sources, warnings);
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
        String summary = truncate(summarizeSql(entry.category(), normalizeSql(entry.sql())));
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
                null);
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
                group.lastTraceId(),
                group.lastRequestMethod(),
                group.lastRequestPath(),
                null,
                group.lastThread(),
                false,
                parentId,
                null);
    }

    /**
     * The id of the REQUEST entry a child signal (SQL/exception) should nest under, or {@code null} when
     * the child carries no trace id, no request shares it, or — the uniqueness guard — more than one
     * request carries it (an ambiguous, likely reused, inbound {@code traceparent}).
     */
    private static String parentFor(String childTraceId, Map<String, String> requestIdByTrace, Set<String> ambiguous) {
        String trace = blankToNull(childTraceId);
        if (trace == null || ambiguous.contains(trace)) {
            return null;
        }
        return requestIdByTrace.get(trace);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String requestSeverity(int status, Long durationMs) {
        if (status >= 500) {
            return SEVERITY_ERROR;
        }
        if (status >= 400) {
            return SEVERITY_WARN;
        }
        if (durationMs != null && durationMs >= SLOW_MS) {
            return SEVERITY_SLOW;
        }
        return SEVERITY_OK;
    }

    /** Collapse runs of whitespace into single spaces and trim, returning "" for null. */
    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * Build a stream summary for a SQL statement. The category prefix (e.g. {@code DDL}, {@code OTHER}) is
     * only kept when it adds information; a statement that already begins with its category keyword
     * ({@code SELECT}, {@code INSERT}, ...) drops it to avoid redundant "SELECT select ..." text.
     */
    private static String summarizeSql(String category, String normalizedSql) {
        String sql = normalizedSql == null ? "" : normalizedSql;
        if (category == null || category.isBlank()) {
            return sql;
        }
        if (sql.length() >= category.length() && sql.regionMatches(true, 0, category, 0, category.length())) {
            return sql;
        }
        return sql.isEmpty() ? category : category + " " + sql;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_SQL_SUMMARY ? value : value.substring(0, MAX_SQL_SUMMARY) + "…";
    }

    private Long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100d * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private Long heapUsed() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap == null ? null : heap.getUsed();
    }

    private Long heapMax() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap == null || heap.getMax() < 0 ? null : heap.getMax();
    }
}
