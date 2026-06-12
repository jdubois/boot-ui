package io.github.jdubois.bootui.autoconfigure.diagnostics;

import io.github.jdubois.bootui.core.dto.DiagnosticsDashboardReport;
import io.github.jdubois.bootui.core.dto.DiagnosticsRequestDto;
import io.github.jdubois.bootui.core.dto.DiagnosticsSourcesDto;
import io.github.jdubois.bootui.core.dto.DiagnosticsTimelineEntryDto;
import io.github.jdubois.bootui.core.dto.DiagnosticsUnattributedDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure correlation engine for the Diagnostics dashboard. It joins normalized diagnostic signals
 * into per-request timelines, primarily by shared {@code traceId} and, when no trace context is
 * available, by best-effort heuristics (matching an HTTP request, or grouping by thread and time).
 *
 * <p>This class has no Spring dependencies so it can be unit-tested directly. The controller is
 * responsible for collecting (and masking) the inputs from the underlying panels.
 */
public final class DiagnosticsCorrelator {

    /** Time window, in milliseconds, used when heuristically matching signals without a trace id. */
    static final long CORRELATION_WINDOW_MS = 2_000L;

    /** Maximum gap, in milliseconds, between same-thread signals grouped into one activity. */
    static final long THREAD_GROUP_GAP_MS = 2_000L;

    /** Cap on the number of timeline entries retained per request, newest events kept. */
    static final int MAX_TIMELINE_PER_REQUEST = 200;

    /** Cap on the number of unattributed sample entries returned. */
    static final int MAX_UNATTRIBUTED_SAMPLE = 100;

    public record HttpSignal(
            long timestamp,
            String traceId,
            String method,
            String path,
            Integer status,
            Long durationMs,
            String principal) {}

    public record SqlSignal(
            long timestamp,
            String traceId,
            String thread,
            String statementType,
            String sql,
            Long durationMs,
            boolean success,
            boolean slow) {}

    public record ExceptionSignal(
            long timestamp,
            String traceId,
            String thread,
            String className,
            String message,
            String requestMethod,
            String requestPath,
            boolean applicationException) {}

    public record SecuritySignal(long timestamp, String principal, String type) {}

    public record SpanSignal(String traceId, String rootName, long startMs, long endMs, boolean error) {}

    public record Inputs(
            List<HttpSignal> http,
            List<SqlSignal> sql,
            List<ExceptionSignal> exceptions,
            List<SecuritySignal> security,
            List<SpanSignal> spans,
            DiagnosticsSourcesDto sources) {

        public Inputs {
            http = http == null ? List.of() : List.copyOf(http);
            sql = sql == null ? List.of() : List.copyOf(sql);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
            security = security == null ? List.of() : List.copyOf(security);
            spans = spans == null ? List.of() : List.copyOf(spans);
        }
    }

    private DiagnosticsCorrelator() {}

    public static DiagnosticsDashboardReport correlate(Inputs inputs, String query, Integer offset, Integer limit) {
        Map<String, Activity> byTrace = new LinkedHashMap<>();
        List<Activity> httpActivities = new ArrayList<>();
        List<DiagnosticsTimelineEntryDto> unattributed = new ArrayList<>();
        int[] unattributedCounts = new int[4]; // sql, exception, security, log

        boolean tracingActive = false;

        // 1. HTTP exchanges anchor trace activities (and stand-alone request activities).
        for (HttpSignal http : inputs.http()) {
            DiagnosticsTimelineEntryDto entry = new DiagnosticsTimelineEntryDto(
                    "HTTP",
                    http.timestamp(),
                    httpTitle(http),
                    null,
                    http.durationMs(),
                    isErrorStatus(http.status()) ? "ERROR" : "INFO",
                    null,
                    false);
            if (hasText(http.traceId())) {
                tracingActive = true;
                Activity activity = byTrace.computeIfAbsent(http.traceId(), Activity::new);
                activity.applyHttpAnchor(http);
                activity.add(entry, "HTTP");
            } else {
                Activity activity = new Activity(null);
                activity.applyHttpAnchor(http);
                activity.add(entry, "HTTP");
                httpActivities.add(activity);
            }
        }

        // 2. Spans enrich (or create) trace activities.
        for (SpanSignal span : inputs.spans()) {
            if (!hasText(span.traceId())) {
                continue;
            }
            tracingActive = true;
            Activity activity = byTrace.computeIfAbsent(span.traceId(), Activity::new);
            activity.applySpanAnchor(span);
        }

        // 3. SQL executions.
        List<SqlSignal> looseSql = new ArrayList<>();
        for (SqlSignal sql : inputs.sql()) {
            if (hasText(sql.traceId())) {
                tracingActive = true;
                Activity activity = byTrace.computeIfAbsent(sql.traceId(), Activity::new);
                activity.add(sqlEntry(sql), "SQL");
                activity.addThread(sql.thread());
            } else {
                looseSql.add(sql);
            }
        }

        // 4. Exceptions.
        List<ExceptionSignal> looseExceptions = new ArrayList<>();
        for (ExceptionSignal ex : inputs.exceptions()) {
            if (hasText(ex.traceId())) {
                tracingActive = true;
                Activity activity = byTrace.computeIfAbsent(ex.traceId(), Activity::new);
                activity.add(exceptionEntry(ex), "EXCEPTION");
                activity.addThread(ex.thread());
            } else {
                looseExceptions.add(ex);
            }
        }

        List<Activity> anchored = new ArrayList<>(byTrace.values());
        anchored.addAll(httpActivities);

        // 5. Exceptions without a trace: try to match an HTTP-anchored request by path, else defer.
        List<ExceptionSignal> threadExceptions = new ArrayList<>();
        for (ExceptionSignal ex : looseExceptions) {
            Activity match = hasText(ex.requestPath()) ? matchByPath(anchored, ex) : null;
            if (match != null) {
                match.add(exceptionEntry(ex), "EXCEPTION");
                match.addThread(ex.thread());
            } else {
                threadExceptions.add(ex);
            }
        }

        // 6. Security events have no trace context: match an HTTP request by principal + time.
        for (SecuritySignal security : inputs.security()) {
            Activity match = matchByPrincipal(anchored, security);
            if (match != null) {
                match.add(securityEntry(security), "SECURITY");
            } else {
                unattributed.add(securityEntry(security));
                unattributedCounts[2]++;
            }
        }

        // 7. Remaining SQL + exceptions: group by thread + time window.
        List<Activity> threadActivities =
                groupByThread(looseSql, threadExceptions, unattributed, unattributedCounts);

        List<Activity> all = new ArrayList<>(byTrace.values());
        all.addAll(httpActivities);
        all.addAll(threadActivities);

        // 8. Keep only activities that carry at least one diagnostic signal, demote the rest.
        List<DiagnosticsRequestDto> requests = new ArrayList<>();
        for (Activity activity : all) {
            if (activity.diagnosticCount() == 0) {
                demote(activity, unattributed, unattributedCounts);
                continue;
            }
            requests.add(activity.toDto());
        }

        requests.sort(Comparator.comparingLong(DiagnosticsRequestDto::startTimestamp).reversed());

        // 9. Filter + page.
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<DiagnosticsRequestDto> filtered = new ArrayList<>();
        for (DiagnosticsRequestDto request : requests) {
            if (normalizedQuery.isEmpty() || matches(request, normalizedQuery)) {
                filtered.add(request);
            }
        }
        int total = filtered.size();
        List<DiagnosticsRequestDto> page = paginate(filtered, offset, limit);

        unattributed.sort(Comparator.comparingLong(DiagnosticsTimelineEntryDto::timestamp).reversed());
        List<DiagnosticsTimelineEntryDto> unattributedSample = unattributed.size() > MAX_UNATTRIBUTED_SAMPLE
                ? new ArrayList<>(unattributed.subList(0, MAX_UNATTRIBUTED_SAMPLE))
                : unattributed;

        DiagnosticsUnattributedDto unattributedDto = new DiagnosticsUnattributedDto(
                unattributedCounts[0],
                unattributedCounts[1],
                unattributedCounts[2],
                unattributedCounts[3],
                unattributedSample);

        return new DiagnosticsDashboardReport(
                true, null, tracingActive, inputs.sources(), total, page, unattributedDto);
    }

    private static List<Activity> groupByThread(
            List<SqlSignal> sql,
            List<ExceptionSignal> exceptions,
            List<DiagnosticsTimelineEntryDto> unattributed,
            int[] unattributedCounts) {
        // Bucket signals by thread.
        Map<String, List<ThreadSignal>> byThread = new LinkedHashMap<>();
        for (SqlSignal entry : sql) {
            byThread.computeIfAbsent(threadKey(entry.thread()), key -> new ArrayList<>())
                    .add(new ThreadSignal(entry.timestamp(), entry.thread(), sqlEntry(entry), "SQL", false));
        }
        for (ExceptionSignal entry : exceptions) {
            byThread.computeIfAbsent(threadKey(entry.thread()), key -> new ArrayList<>())
                    .add(new ThreadSignal(
                            entry.timestamp(), entry.thread(), exceptionEntry(entry), "EXCEPTION", true));
        }

        List<Activity> result = new ArrayList<>();
        for (List<ThreadSignal> signals : byThread.values()) {
            signals.sort(Comparator.comparingLong(ThreadSignal::timestamp));
            List<ThreadSignal> cluster = new ArrayList<>();
            long previous = Long.MIN_VALUE;
            for (ThreadSignal signal : signals) {
                if (!cluster.isEmpty() && signal.timestamp() - previous > THREAD_GROUP_GAP_MS) {
                    flushCluster(cluster, result, unattributed, unattributedCounts);
                    cluster = new ArrayList<>();
                }
                cluster.add(signal);
                previous = signal.timestamp();
            }
            flushCluster(cluster, result, unattributed, unattributedCounts);
        }
        return result;
    }

    private static void flushCluster(
            List<ThreadSignal> cluster,
            List<Activity> result,
            List<DiagnosticsTimelineEntryDto> unattributed,
            int[] unattributedCounts) {
        if (cluster.isEmpty()) {
            return;
        }
        boolean hasException = cluster.stream().anyMatch(ThreadSignal::error);
        if (cluster.size() == 1 && !hasException) {
            // A lone, non-error signal is not a meaningful "request"; surface it as unattributed.
            ThreadSignal only = cluster.get(0);
            unattributed.add(only.entry());
            countUnattributed(unattributedCounts, only.kind());
            return;
        }
        String thread = cluster.get(0).thread();
        Activity activity = new Activity("thread:" + threadKey(thread) + ":" + cluster.get(0).timestamp());
        activity.thread = thread;
        for (ThreadSignal signal : cluster) {
            activity.add(signal.entry(), signal.kind());
            activity.addThread(signal.thread());
        }
        result.add(activity);
    }

    private static void demote(
            Activity activity, List<DiagnosticsTimelineEntryDto> unattributed, int[] unattributedCounts) {
        for (DiagnosticsTimelineEntryDto entry : activity.timeline) {
            unattributed.add(entry);
            countUnattributed(unattributedCounts, entry.kind());
        }
    }

    private static void countUnattributed(int[] counts, String kind) {
        switch (kind) {
            case "SQL" -> counts[0]++;
            case "EXCEPTION" -> counts[1]++;
            case "SECURITY" -> counts[2]++;
            case "LOG" -> counts[3]++;
            default -> {
                // HTTP/SPAN events are not counted as unattributed diagnostic signals.
            }
        }
    }

    private static Activity matchByPath(List<Activity> activities, ExceptionSignal ex) {
        Activity best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Activity activity : activities) {
            if (activity.path == null || !activity.path.equals(ex.requestPath())) {
                continue;
            }
            if (hasText(ex.requestMethod()) && activity.method != null && !activity.method.equals(ex.requestMethod())) {
                continue;
            }
            long delta = Math.abs(activity.anchorTimestamp() - ex.timestamp());
            if (delta <= CORRELATION_WINDOW_MS && delta < bestDelta) {
                best = activity;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static Activity matchByPrincipal(List<Activity> activities, SecuritySignal security) {
        if (!hasText(security.principal())) {
            return null;
        }
        Activity best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Activity activity : activities) {
            if (activity.principal == null || !activity.principal.equals(security.principal())) {
                continue;
            }
            long delta = Math.abs(activity.anchorTimestamp() - security.timestamp());
            if (delta <= CORRELATION_WINDOW_MS && delta < bestDelta) {
                best = activity;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static List<DiagnosticsRequestDto> paginate(
            List<DiagnosticsRequestDto> items, Integer offset, Integer limit) {
        int from = offset == null || offset < 0 ? 0 : Math.min(offset, items.size());
        int size = limit == null || limit <= 0 ? items.size() : limit;
        int to = Math.min(from + size, items.size());
        return new ArrayList<>(items.subList(from, to));
    }

    private static boolean matches(DiagnosticsRequestDto request, String query) {
        if (contains(request.label(), query)
                || contains(request.path(), query)
                || contains(request.traceId(), query)
                || contains(request.principal(), query)) {
            return true;
        }
        for (DiagnosticsTimelineEntryDto entry : request.timeline()) {
            if (contains(entry.title(), query) || contains(entry.detail(), query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static DiagnosticsTimelineEntryDto sqlEntry(SqlSignal sql) {
        String type = hasText(sql.statementType()) ? sql.statementType() : "SQL";
        String severity = sql.success() ? (sql.slow() ? "WARN" : "INFO") : "ERROR";
        return new DiagnosticsTimelineEntryDto(
                "SQL", sql.timestamp(), type, sql.sql(), sql.durationMs(), severity, sql.thread(), sql.slow());
    }

    private static DiagnosticsTimelineEntryDto exceptionEntry(ExceptionSignal ex) {
        return new DiagnosticsTimelineEntryDto(
                "EXCEPTION", ex.timestamp(), ex.className(), ex.message(), null, "ERROR", ex.thread(), false);
    }

    private static DiagnosticsTimelineEntryDto securityEntry(SecuritySignal security) {
        return new DiagnosticsTimelineEntryDto(
                "SECURITY", security.timestamp(), security.type(), security.principal(), null, "WARN", null, false);
    }

    private static String httpTitle(HttpSignal http) {
        String method = hasText(http.method()) ? http.method() : "HTTP";
        String path = hasText(http.path()) ? http.path() : "";
        String status = http.status() == null ? "" : " \u2192 " + http.status();
        return (method + " " + path).trim() + status;
    }

    private static boolean isErrorStatus(Integer status) {
        return status != null && status >= 500;
    }

    private static String threadKey(String thread) {
        return hasText(thread) ? thread : "?";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ThreadSignal(
            long timestamp, String thread, DiagnosticsTimelineEntryDto entry, String kind, boolean error) {}

    /** Mutable accumulator for a single correlated request while the report is being built. */
    private static final class Activity {

        private final String traceId;
        private String id;
        private String method;
        private String path;
        private Integer status;
        private Long durationMs;
        private String principal;
        private String label;
        private String thread;
        private long anchorTimestamp = Long.MAX_VALUE;
        private final List<DiagnosticsTimelineEntryDto> timeline = new ArrayList<>();
        private int httpCount;
        private int sqlCount;
        private int exceptionCount;
        private int securityCount;
        private int logCount;
        private boolean hasError;

        Activity(String traceId) {
            this.traceId = traceId;
            this.id = traceId;
        }

        void applyHttpAnchor(HttpSignal http) {
            if (path == null && hasText(http.path())) {
                path = http.path();
            }
            if (method == null && hasText(http.method())) {
                method = http.method();
            }
            if (status == null) {
                status = http.status();
            }
            if (durationMs == null) {
                durationMs = http.durationMs();
            }
            if (principal == null && hasText(http.principal())) {
                principal = http.principal();
            }
            anchorTimestamp = Math.min(anchorTimestamp, http.timestamp());
            if (label == null) {
                label = httpTitle(http);
            }
        }

        void applySpanAnchor(SpanSignal span) {
            if (label == null && hasText(span.rootName())) {
                label = span.rootName();
            }
            if (durationMs == null && span.endMs() >= span.startMs()) {
                durationMs = span.endMs() - span.startMs();
            }
            anchorTimestamp = Math.min(anchorTimestamp, span.startMs());
            hasError |= span.error();
        }

        void add(DiagnosticsTimelineEntryDto entry, String kind) {
            timeline.add(entry);
            switch (kind) {
                case "HTTP" -> httpCount++;
                case "SQL" -> sqlCount++;
                case "EXCEPTION" -> exceptionCount++;
                case "SECURITY" -> securityCount++;
                case "LOG" -> logCount++;
                default -> {
                    // no-op
                }
            }
            if ("ERROR".equals(entry.severity())) {
                hasError = true;
            }
        }

        void addThread(String value) {
            if (thread == null && hasText(value)) {
                thread = value;
            }
        }

        int diagnosticCount() {
            return sqlCount + exceptionCount + securityCount + logCount;
        }

        long anchorTimestamp() {
            return anchorTimestamp == Long.MAX_VALUE ? earliestTimestamp() : anchorTimestamp;
        }

        long earliestTimestamp() {
            long min = Long.MAX_VALUE;
            for (DiagnosticsTimelineEntryDto entry : timeline) {
                min = Math.min(min, entry.timestamp());
            }
            return min == Long.MAX_VALUE ? 0L : min;
        }

        DiagnosticsRequestDto toDto() {
            List<DiagnosticsTimelineEntryDto> sorted = new ArrayList<>(timeline);
            sorted.sort(Comparator.comparingLong(DiagnosticsTimelineEntryDto::timestamp));
            if (sorted.size() > MAX_TIMELINE_PER_REQUEST) {
                sorted = new ArrayList<>(sorted.subList(sorted.size() - MAX_TIMELINE_PER_REQUEST, sorted.size()));
            }
            long start = sorted.isEmpty() ? earliestTimestamp() : sorted.get(0).timestamp();
            String resolvedLabel = label;
            if (resolvedLabel == null) {
                resolvedLabel = thread != null ? thread : (traceId != null ? "trace " + shortTrace(traceId) : "Activity");
            }
            return new DiagnosticsRequestDto(
                    id == null ? resolvedLabel : id,
                    correlation(),
                    traceId,
                    method,
                    path,
                    status,
                    durationMs,
                    principal,
                    start,
                    resolvedLabel,
                    httpCount,
                    sqlCount,
                    exceptionCount,
                    securityCount,
                    logCount,
                    hasError,
                    sorted);
        }

        private String correlation() {
            if (hasText(traceId)) {
                return "TRACE";
            }
            if (method != null || path != null) {
                return "REQUEST";
            }
            if (timeline.size() <= 1) {
                return "SINGLE";
            }
            return "THREAD";
        }

        private static String shortTrace(String traceId) {
            return traceId.length() > 8 ? traceId.substring(0, 8) : traceId;
        }
    }
}
