package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Framework-neutral assembly of the Live Activity merged stream + KPI summary from already-masked source
 * reports. The Spring adapter has its own richer, controller-fed service (with per-request signal
 * correlation and thread attribution); the Quarkus adapter feeds this assembler the four signal sources it
 * captures — HTTP requests, SQL trace, exceptions, and security/audit events — which are merged into one
 * reverse-chronological feed with JVM heap and per-type KPIs.
 *
 * <p>Inputs are already masked and self-filtered by their owning engine services before they reach this
 * shape, so the assembler only normalizes, severities, merges, correlates and caps.</p>
 *
 * <p><strong>Correlation is data-driven on the shared trace id.</strong> Spring's thread-per-request model
 * lets it attribute signals by serving thread, but on the Vert.x event loop a thread does not map to a
 * single request, so that strategy is unportable. Instead, when the adapter stamps a distributed-trace id on
 * each signal (the Quarkus adapter reads {@code Span.current()} when OpenTelemetry is present, whose context
 * propagates across the event-loop→worker hops), this assembler nests each SQL/exception/security entry
 * under the REQUEST entry sharing the same trace id by setting its {@code parentId}, using the shared
 * {@link TraceCorrelationIndex} primitive. A child is only nested when <em>exactly one</em> request carries
 * that trace id (a uniqueness guard against a reused inbound {@code traceparent}). When no trace id is
 * stamped — OpenTelemetry absent — every {@code correlationId} is {@code null}, no {@code parentId} is set,
 * and the feed renders flat (the honest status quo). A correlated security event also stamps
 * {@code securedPrincipal} on its parent REQUEST entry (falling back from the request's own principal, when
 * Quarkus's security layer directly authenticated it) — see {@link #report}. {@code profileable} is set by
 * the Quarkus adapter itself as a thin post-processing step over this assembler's output (not by this class),
 * once a REQUEST entry's exchange carries a resolvable trace id — that same trace id is what
 * {@link RequestProfileAssembler} uses to serve the reduced, trace-id-only per-request profile drill-down at
 * {@code GET /bootui/api/activity/request/{id}} on Quarkus (Spring's fuller, thread/time-window-heuristic
 * profiler stays out of scope for that port).</p>
 */
public final class LiveActivityAssembler {

    private static final long SLOW_MS = 500L;

    /** Maximum characters of a SQL statement shown inline in a stream summary. */
    private static final int MAX_SQL_SUMMARY = 160;

    private static final String TYPE_REQUEST = "REQUEST";
    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_EXCEPTION = "EXCEPTION";
    private static final String TYPE_SECURITY = "SECURITY";

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
     * @param securityEvents already-masked security/audit events (newest-first), or {@code null}; ignored
     *     unless {@code securityAvailable}
     * @param securityAvailable whether the security-event source is present and feeding (Quarkus's security
     *     capability is present and {@code quarkus.security.events.enabled=true})
     * @param healthStatus current health status, or {@code null}
     * @param limit maximum merged entries to return, or {@code 0}/negative for no cap
     */
    public LiveActivityReport report(
            HttpExchangesReport requests,
            List<SqlTraceEntryDto> sqlEntries,
            boolean sqlAvailable,
            String sqlUnavailableWarning,
            List<ExceptionGroupDto> exceptionGroups,
            List<SecurityLogEventDto> securityEvents,
            boolean securityAvailable,
            String healthStatus,
            int limit) {

        List<HttpExchangeDto> exchanges = requests == null ? List.of() : requests.exchanges();
        List<SqlTraceEntryDto> sql = !sqlAvailable || sqlEntries == null ? List.of() : sqlEntries;
        List<ExceptionGroupDto> exceptions = exceptionGroups == null ? List.of() : exceptionGroups;
        List<SecurityLogEventDto> security = !securityAvailable || securityEvents == null ? List.of() : securityEvents;

        List<ActivityEntryDto> entries = new ArrayList<>();

        long errors = 0;
        long slowest = 0;
        String slowestPath = null;
        List<Long> durations = new ArrayList<>();

        // Map each non-blank request trace id to its REQUEST entry id, tracking trace ids shared by more
        // than one request so an ambiguous (reused) trace never nests a child under the wrong request.
        TraceCorrelationIndex traceIndex = TraceCorrelationIndex.of(exchanges);

        // Correlate security events to their owning request BEFORE building REQUEST entries (an immutable
        // record can't be patched after construction), so a uniquely-matched event's principal can be
        // stamped onto the request as `securedPrincipal`. Newest-first iteration + putIfAbsent means the
        // most recent correlated event's principal wins when more than one shares a request's trace id; a
        // blank/anonymous principal is never recorded so it can't paint a misleading "secured" badge on an
        // unauthenticated request.
        Map<String, String> securedPrincipalByRequestId = new HashMap<>();
        for (SecurityLogEventDto event : security) {
            String requestId = traceIndex.parentRequestId(event.traceId());
            String principal = blankToNull(event.principal());
            if (requestId != null && principal != null) {
                securedPrincipalByRequestId.putIfAbsent(requestId, principal);
            }
        }

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
            // The request's own principal (Quarkus's security layer authenticated it directly, e.g. via
            // rc.user()) takes precedence as the more direct signal; fall back to a correlated security
            // event's principal only when the request itself carried none.
            String securedPrincipal = e.principal() != null ? e.principal() : securedPrincipalByRequestId.get(e.id());
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
                    securedPrincipal));
        }

        Long slowestQuery = null;
        for (SqlTraceEntryDto s : sql) {
            if (slowestQuery == null || s.durationMillis() > slowestQuery) {
                slowestQuery = s.durationMillis();
            }
            entries.add(toSqlEntry(s, traceIndex.parentRequestId(s.traceId())));
        }

        for (ExceptionGroupDto g : exceptions) {
            entries.add(toExceptionEntry(g, traceIndex.parentRequestId(g.lastTraceId())));
        }

        for (SecurityLogEventDto event : security) {
            entries.add(toSecurityEntry(event, traceIndex.parentRequestId(event.traceId())));
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
        if (securityAvailable) {
            sources.add("security");
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
     * Build a SECURITY entry for an already-masked audit/security event. Severity mirrors Spring's
     * {@code LiveActivityService.toSecurityEntry} mapping: an event type naming a failure or a denial is a
     * {@code WARN} (worth a glance), anything else (e.g. an authentication success) is {@code OK}. The id
     * is a stable content hash (see {@link SecurityActivityIds}), not the event's position in this list.
     */
    private ActivityEntryDto toSecurityEntry(SecurityLogEventDto event, String parentId) {
        String type = event.type() == null ? "" : event.type();
        String upperType = type.toUpperCase(Locale.ROOT);
        String severity = upperType.contains("FAILURE") || upperType.contains("DENIED") ? SEVERITY_WARN : SEVERITY_OK;
        String principal = blankToNull(event.principal());
        String summary = (type + (principal == null ? "" : " · " + principal)).trim();
        return new ActivityEntryDto(
                SecurityActivityIds.stableId(event),
                TYPE_SECURITY,
                parseEpochMillis(event.timestamp()),
                severity,
                summary,
                null,
                null,
                event.traceId(),
                null,
                null,
                null,
                null,
                false,
                parentId,
                null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Parse an ISO-8601 instant to epoch millis, returning {@code 0} for null/blank/unparseable input. */
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
