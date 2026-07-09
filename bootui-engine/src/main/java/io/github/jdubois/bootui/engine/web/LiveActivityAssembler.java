package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityEntries;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
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
 * correlation and thread attribution); the WebFlux and Quarkus adapters feed this assembler the signal
 * sources they capture — HTTP requests, SQL trace, exceptions, security/audit events, cache accesses,
 * captured {@code @Scheduled} task executions, captured emails, and optionally Kafka messaging — which are
 * merged into one reverse-chronological feed with JVM heap and per-type KPIs.
 *
 * <p>Inputs are already masked and self-filtered by their owning engine services before they reach this
 * shape, so the assembler only normalizes, severities, merges, correlates and caps.</p>
 *
 * <p><strong>Correlation is data-driven on the shared trace id.</strong> Spring's thread-per-request model
 * lets it attribute signals by serving thread, but on the Vert.x event loop a thread does not map to a
 * single request, so that strategy is unportable. Instead, when the adapter stamps a distributed-trace id on
 * each signal (the Quarkus adapter reads {@code Span.current()} when OpenTelemetry is present, whose context
 * propagates across the event-loop→worker hops), this assembler nests each SQL/exception/security/MAIL entry
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
 *
 * <p><strong>Cache access (the {@code CACHE} entry type / {@code cacheHitRatioPercent} KPI) is
 * trace-id-correlated too</strong>, same as SQL/exceptions/security: the Spring WebFlux adapter feeds
 * {@link CacheActivityEvent}s captured by the shared engine {@code CacheActivityRecorder} (fed in turn by
 * decorating {@code CacheManager}/{@code Cache} beans — the same recorder the Spring servlet adapter's own
 * richer {@code LiveActivityService} uses). Quarkus has no comparable capture seam yet (see
 * {@code docs/PLAN.md} §3.4): {@code quarkus-cache}'s build-time-woven {@code @CacheResult}/
 * {@code @CacheInvalidate} interceptors cast their resolved {@code Cache} to an internal, non-public type,
 * so a Spring-style decorator over the public {@code Cache} interface is not a viable interception seam
 * there. The Quarkus adapter therefore always passes {@code cacheAvailable=false}, and
 * {@code cacheHitRatioPercent} renders {@code null} on that adapter, exactly as before.</p>
 *
 * <p><strong>Scheduled-task executions ({@code SCHEDULED_TASK} entries) do not correlate on trace id at
 * all</strong>, unlike every other signal above: a background {@code @Scheduled} job runs on its own thread
 * outside any HTTP request or distributed trace, so it never has a request to nest under (its own
 * {@code parentId} is always {@code null}). Instead, the relationship runs the other way — a captured
 * exception that no HTTP request claims by trace id falls back to a serving-thread + time-window join
 * against the {@code @Scheduled} execution that produced it ({@link #matchScheduledTaskParent}, mirroring
 * the Spring adapter's {@code LiveActivityService.matchScheduledTaskParent}), nesting as that run's
 * {@code EXCEPTION} child. {@code scheduledTaskFailureCount} counts failed runs currently retained
 * regardless of correlation.</p>
 *
 * <p><strong>Kafka produce/consume outcomes (the {@code MESSAGING} entry type) render top-level, with no
 * request-parent correlation attempted</strong>, unlike SQL/exceptions/security/cache above: BootUI has no
 * trace id available on the producer/consumer thread today, so every {@link KafkaActivityEntries#toEntry}
 * mapping is flat by design (see {@code docs/PLAN.md} §3.4 for the nesting this can grow into once
 * messaging spans carry a correlation id).</p>
 */
public final class LiveActivityAssembler {

    private static final long SLOW_MS = 500L;

    /** Maximum characters of a SQL statement shown inline in a stream summary. */
    private static final int MAX_SQL_SUMMARY = 160;

    /**
     * Window-slack tolerance (in both directions) when joining an exception to an owning
     * {@code @Scheduled} execution by serving thread + time window. Matches the Spring adapter's
     * {@code ActivitySql.WINDOW_SLACK_MS}.
     */
    private static final long SCHEDULED_TASK_WINDOW_SLACK_MS = 50L;

    private static final String TYPE_REQUEST = "REQUEST";
    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_EXCEPTION = "EXCEPTION";
    private static final String TYPE_SECURITY = "SECURITY";
    private static final String TYPE_MAIL = "MAIL";
    private static final String TYPE_CACHE = "CACHE";
    private static final String TYPE_SCHEDULED_TASK = "SCHEDULED_TASK";

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
     * @param cacheEvents captured cache accesses (hit/miss/put/evict/clear, newest-first), or {@code null};
     *     ignored unless {@code cacheAvailable}
     * @param cacheAvailable whether the cache-access source is present and feeding (Spring WebFlux with
     *     {@code bootui.cache.activity-capture-enabled}; always {@code false} on Quarkus today)
     * @param scheduledRuns captured {@code @Scheduled} task executions (newest-first), or {@code null}.
     *     There is no request to nest a scheduled-task entry under (it runs on a background thread, not the
     *     event loop), so its {@code parentId} is always {@code null}; a correlated exception falls back to
     *     attaching to the scheduled-task run that produced it (see {@link #matchScheduledTaskParent}) when
     *     no HTTP request claims it.
     * @param healthStatus current health status, or {@code null}
     * @param limit maximum merged entries to return, or {@code 0}/negative for no cap
     * @param kafkaMessages captured Kafka produce/consume outcomes (newest-first), or {@code null}; ignored
     *     unless {@code kafkaAvailable}. Rendered top-level (no request-parent correlation), since there is
     *     no trace id available on the producer/consumer thread today — see {@code docs/PLAN.md} §3.4.
     * @param kafkaAvailable whether the Kafka capture source is present and feeding ({@code KafkaTemplate}/
     *     {@code @KafkaListener} beans on Spring, SmallRye Reactive Messaging channels on Quarkus)
     * @param emailMessages already-masked captured outgoing emails (newest-first), or {@code null}; ignored
     *     unless {@code emailAvailable}. Email capture stamps the active trace id alongside the sending
     *     thread, so {@code MAIL} entries can be nested under the uniquely matching REQUEST entry that
     *     shares that trace id, exactly like SQL/exceptions/security/cache above.
     * @param emailAvailable whether a mail sender is present and the Email Viewer panel is feeding
     */
    public LiveActivityReport report(
            HttpExchangesReport requests,
            List<SqlTraceEntryDto> sqlEntries,
            boolean sqlAvailable,
            String sqlUnavailableWarning,
            List<ExceptionGroupDto> exceptionGroups,
            List<SecurityLogEventDto> securityEvents,
            boolean securityAvailable,
            List<CacheActivityEvent> cacheEvents,
            boolean cacheAvailable,
            List<ScheduledTaskRunStore.Run> scheduledRuns,
            String healthStatus,
            int limit,
            List<CapturedMessage> kafkaMessages,
            boolean kafkaAvailable,
            List<EmailMessageDto> emailMessages,
            boolean emailAvailable) {

        List<HttpExchangeDto> exchanges = requests == null ? List.of() : requests.exchanges();
        List<SqlTraceEntryDto> sql = !sqlAvailable || sqlEntries == null ? List.of() : sqlEntries;
        List<ExceptionGroupDto> exceptions = exceptionGroups == null ? List.of() : exceptionGroups;
        List<CacheActivityEvent> cache = !cacheAvailable || cacheEvents == null ? List.of() : cacheEvents;
        List<ScheduledTaskRunStore.Run> scheduled = scheduledRuns == null ? List.of() : scheduledRuns;
        List<SecurityLogEventDto> security = !securityAvailable || securityEvents == null ? List.of() : securityEvents;
        List<CapturedMessage> kafka = !kafkaAvailable || kafkaMessages == null ? List.of() : kafkaMessages;
        List<EmailMessageDto> emails = !emailAvailable || emailMessages == null ? List.of() : emailMessages;

        List<ActivityEntryDto> entries = new ArrayList<>();

        long errors = 0;
        long slowest = 0;
        String slowestPath = null;
        List<Long> durations = new ArrayList<>();

        // Map each non-blank request trace id to its REQUEST entry id, tracking trace ids shared by more
        // than one request so an ambiguous (reused) trace never nests a child under the wrong request.
        TraceCorrelationIndex traceIndex = TraceCorrelationIndex.of(exchanges);

        // One anchor per captured scheduled-task execution, used as the exception-correlation fallback
        // tier below when no HTTP request claims the exception (see matchScheduledTaskParent).
        List<ScheduledTaskAnchor> scheduledTaskAnchors = buildScheduledTaskAnchors(scheduled);

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

        // Same idea, computed up front so the REQUEST loop below can flag N+1 suspicion per request: group
        // each request's own correlated SQL executions by owning request id, reusing the shared
        // TraceCorrelationIndex uniqueness guard so an ambiguous trace id never attributes SQL to the wrong
        // request.
        Map<String, List<SqlTraceEntryDto>> sqlByRequestId = new HashMap<>();
        for (SqlTraceEntryDto entry : sql) {
            String requestId = traceIndex.parentRequestId(entry.traceId());
            if (requestId != null) {
                sqlByRequestId
                        .computeIfAbsent(requestId, id -> new ArrayList<>())
                        .add(entry);
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
            boolean sqlNPlusOneSuspected = SqlTraceGrouping.anySuspectedNPlusOne(
                    sqlByRequestId.getOrDefault(e.id(), List.of()), SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD);
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
                    securedPrincipal,
                    sqlNPlusOneSuspected));
        }

        Long slowestQuery = null;
        for (SqlTraceEntryDto s : sql) {
            if (slowestQuery == null || s.durationMillis() > slowestQuery) {
                slowestQuery = s.durationMillis();
            }
            entries.add(toSqlEntry(s, traceIndex.parentRequestId(s.traceId())));
        }

        for (ExceptionGroupDto g : exceptions) {
            String parentId = traceIndex.parentRequestId(g.lastTraceId());
            if (parentId == null) {
                // No owning HTTP request: fall back to attributing the exception to the background
                // @Scheduled execution that produced it (serving-thread + time-window join — the same
                // strategy the Spring adapter's matchScheduledTaskParent uses; there is no distributed
                // trace id to join on for a background job, unlike the REQUEST case above).
                parentId = matchScheduledTaskParent(g, scheduledTaskAnchors);
            }
            entries.add(toExceptionEntry(g, parentId));
        }

        for (SecurityLogEventDto event : security) {
            entries.add(toSecurityEntry(event, traceIndex.parentRequestId(event.traceId())));
        }

        for (CacheActivityEvent event : cache) {
            entries.add(toCacheEntry(event, traceIndex.parentRequestId(event.traceId())));
        }

        for (ScheduledTaskRunStore.Run run : scheduled) {
            entries.add(toScheduledTaskEntry(run));
        }

        for (CapturedMessage message : kafka) {
            entries.add(KafkaActivityEntries.toEntry(message));
        }

        for (EmailMessageDto message : emails) {
            entries.add(toEmailEntry(message, traceIndex.parentRequestId(message.traceId())));
        }

        entries.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (ActivityEntryDto entry : entries) {
            typeCounts.merge(entry.type(), 1, Integer::sum);
        }

        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
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
        if (cacheAvailable) {
            sources.add("cache");
        }
        if (!scheduled.isEmpty()) {
            sources.add("scheduled-tasks");
        }
        if (kafkaAvailable) {
            sources.add("kafka");
        }
        if (emailAvailable) {
            sources.add("email");
        }

        List<String> warnings = new ArrayList<>();
        if (!sqlAvailable && sqlUnavailableWarning != null && !sqlUnavailableWarning.isBlank()) {
            warnings.add(sqlUnavailableWarning);
        }

        Double cacheHitRatioPercent = null;
        if (cacheAvailable && !cache.isEmpty()) {
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

        int scheduledTaskFailureCount =
                (int) scheduled.stream().filter(run -> !run.success()).count();

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
                heapMax(),
                cacheHitRatioPercent,
                scheduledTaskFailureCount);
        return new LiveActivityReport(true, entries, typeCounts, kpis, sources, warnings);
    }

    /**
     * Build a {@code CACHE} entry for a captured cache access. Mirrors the Spring servlet
     * {@code LiveActivityService.toCacheEntry} mapping: a {@code MISS} is a {@code WARN} (worth a glance),
     * every other operation ({@code HIT}/{@code PUT}/{@code EVICT}/{@code CLEAR}) is {@code OK}. Only a
     * short key hash is ever surfaced as {@code detail} (never the raw key), and a whole-cache
     * {@code CLEAR} carries no key at all.
     */
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
                group.lastTraceId(),
                group.lastRequestMethod(),
                group.lastRequestPath(),
                null,
                group.lastThread(),
                false,
                parentId,
                null,
                false);
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
                null,
                false);
    }

    private ActivityEntryDto toEmailEntry(EmailMessageDto message, String parentId) {
        String to = message.to().isEmpty() ? "" : String.join(", ", message.to());
        String subject = message.subject() == null ? "(no subject)" : message.subject();
        String detail = to.isBlank() ? null : "to " + to;
        if (!message.sent()) {
            detail = (detail == null ? "" : detail + " · ") + "dev-trap: not sent";
        }
        return new ActivityEntryDto(
                message.id(),
                TYPE_MAIL,
                message.timestamp(),
                message.sent() ? SEVERITY_OK : SEVERITY_WARN,
                subject,
                detail,
                null,
                message.traceId(),
                null,
                null,
                null,
                message.thread(),
                false,
                parentId,
                null,
                false);
    }

    /**
     * Build a {@code SCHEDULED_TASK} entry for a captured {@code @Scheduled} execution, mirroring Spring's
     * {@code LiveActivityService.toScheduledTaskEntry}: there is no request to nest this entry itself under
     * (it runs on a background thread), so its own {@code parentId} is always {@code null}; a run that
     * threw is flagged {@code ERROR}, one slower than the shared request-slow threshold is flagged
     * {@code SLOW}, otherwise {@code OK}. A failure is always summarized inline via {@code detail} (the run
     * recorder observes the exception directly), and — when that same failure is independently captured
     * into the shared exception log buffer — it additionally nests as a full {@code EXCEPTION} child entry
     * under this one, exactly like a request's failure does today; see {@link #matchScheduledTaskParent}.
     */
    private ActivityEntryDto toScheduledTaskEntry(ScheduledTaskRunStore.Run run) {
        String severity;
        if (!run.success()) {
            severity = SEVERITY_ERROR;
        } else if (run.durationMs() >= SLOW_MS) {
            severity = SEVERITY_SLOW;
        } else {
            severity = SEVERITY_OK;
        }
        String detail = run.success() ? null : run.exceptionClassName() + messageSuffix(run.message());
        return new ActivityEntryDto(
                "sched-" + run.sequence(),
                TYPE_SCHEDULED_TASK,
                run.startTimestamp(),
                severity,
                run.runnable(),
                detail,
                run.durationMs(),
                null,
                null,
                null,
                null,
                run.thread(),
                false,
                null,
                null,
                false);
    }

    /** Suffixes a scheduled-task failure summary with its exception message, when one was captured. */
    private static String messageSuffix(String message) {
        return message == null || message.isBlank() ? "" : ": " + message;
    }

    /**
     * Resolves the {@code @Scheduled} execution an exception group belongs to when no HTTP request already
     * claimed it (by trace id): an exact serving-thread + time-window join against the run's execution
     * window, mirroring the Spring adapter's {@code LiveActivityService.matchScheduledTaskParent} (there is
     * no method/path to join on for a background job, and no trace id either, since scheduled executions
     * are not distributed-trace participants). Returns {@code null} when no run's window uniquely covers
     * the exception, so the entry stays top-level rather than being mis-attributed.
     */
    private static String matchScheduledTaskParent(ExceptionGroupDto group, List<ScheduledTaskAnchor> anchors) {
        String thread = group.lastThread();
        if (thread == null || anchors.isEmpty()) {
            return null;
        }
        long ts = group.lastSeen();
        String found = null;
        for (ScheduledTaskAnchor anchor : anchors) {
            if (thread.equals(anchor.thread()) && anchor.covers(ts)) {
                if (found != null) {
                    return null;
                }
                found = anchor.id();
            }
        }
        return found;
    }

    /**
     * Builds one {@link ScheduledTaskAnchor} per captured {@code @Scheduled} execution, so a correlated
     * exception can be attached to the run that produced it via {@link #matchScheduledTaskParent}.
     */
    private static List<ScheduledTaskAnchor> buildScheduledTaskAnchors(List<ScheduledTaskRunStore.Run> runs) {
        List<ScheduledTaskAnchor> anchors = new ArrayList<>(runs.size());
        for (ScheduledTaskRunStore.Run run : runs) {
            long start = run.startTimestamp();
            anchors.add(
                    new ScheduledTaskAnchor("sched-" + run.sequence(), start, start + run.durationMs(), run.thread()));
        }
        return anchors;
    }

    /**
     * A captured {@code @Scheduled} execution reduced to what is needed to attach a correlated exception to
     * it: its {@code SCHEDULED_TASK} entry id, its execution window, and the thread it ran on.
     */
    private record ScheduledTaskAnchor(String id, long start, long end, String thread) {
        boolean covers(long timestamp) {
            return timestamp >= start - SCHEDULED_TASK_WINDOW_SLACK_MS
                    && timestamp <= end + SCHEDULED_TASK_WINDOW_SLACK_MS;
        }
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

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
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
