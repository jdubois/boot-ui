package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlAttributionBucketDto;
import io.github.jdubois.bootui.core.dto.SqlRouteAttributionDto;
import io.github.jdubois.bootui.core.dto.SqlRouteRankingDto;
import io.github.jdubois.bootui.core.dto.SqlRouteStatementDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attributes retained SQL executions to the inbound request routes responsible for them.
 *
 * <p>The correlation is the tiered, uniqueness-guarded strategy the Transactions and Live Activity
 * per-request views already use, applied in the engine so all three adapters produce the same result from
 * the same evidence:</p>
 *
 * <ol>
 *   <li><b>Trace id</b> — an exact join when the execution and exactly one captured request carry the same
 *       distributed-trace id. A trace id shared by several captured requests is treated as ambiguous, never
 *       resolved to one of them.</li>
 *   <li><b>Serving thread</b> — offered only by a runtime that serves a request start to finish on one
 *       worker thread, and only when exactly one such request's window contains the execution. Spring
 *       WebFlux and Quarkus do not offer this tier, because neither Reactor Netty nor the Vert.x event loop
 *       provides the invariant it depends on.</li>
 *   <li><b>Time window</b> — the weakest tier, used only when exactly one captured request was in flight
 *       when the statement ran. Two overlapping candidates make the execution ambiguous rather than
 *       arbitrarily assigned.</li>
 * </ol>
 *
 * <p>Trace context is decisive, in both directions. An execution that carries a trace id no captured
 * request carries is reported as unattributed rather than handed to the weaker tiers: the owning request
 * has normally aged out of the bounded exchange buffer, and every remaining candidate is provably a
 * different trace. Thread reuse and window overlap must never overrule that.</p>
 *
 * <p>Executions that no tier decides are never dropped and never guessed at: they land in an explicit
 * {@code unattributed} bucket (no candidate request at all — background jobs, startup work, migrations) or
 * an {@code ambiguous} bucket (several equally plausible candidates), both of which the panel shows
 * alongside the routes so every share still reconciles with the retained window.</p>
 */
public final class SqlRouteAttribution {

    /** Slack applied to a request window, matching the Live Activity correlator's own tolerance. */
    static final long WINDOW_SLACK_MS = 50L;

    /** Routes returned; further routes are counted but not serialized, bounding high-cardinality paths. */
    public static final int MAX_ROUTES = 20;

    /** Statements listed under each route, bounding the route-by-statement cross product. */
    public static final int MAX_STATEMENTS_PER_ROUTE = 5;

    /** Correlation tiers an adapter can offer, reported to the browser so gaps are explicit. */
    public enum Correlation {
        TRACE_ID,
        SERVING_THREAD,
        TIME_WINDOW
    }

    private SqlRouteAttribution() {}

    /**
     * Attributes {@code entries} to routes derived from {@code requests}.
     *
     * @param entries the retained executions, already masked by the caller
     * @param requests captured inbound requests to attribute against; may be empty
     * @param supported the correlation tiers this runtime can honestly offer
     * @param templates the application's declared route templates, used to label a request whose capture
     *     point could not supply one; {@link RouteTemplateResolver#empty()} when none are known
     * @param totalRetainedDurationMicros total retained database time in microseconds, so every share uses
     *     the same denominator as the statement ranking rather than a locally recomputed one
     */
    public static SqlRouteAttributionDto attribute(
            List<SqlTraceEntryDto> entries,
            List<SqlRequestEvidence> requests,
            Set<Correlation> supported,
            RouteTemplateResolver templates,
            long totalRetainedDurationMicros) {
        List<SqlTraceEntryDto> executions = entries == null ? List.of() : entries;
        List<SqlRequestEvidence> candidates = requests == null ? List.of() : requests;
        Set<Correlation> tiers = supported == null || supported.isEmpty() ? Set.of(Correlation.TRACE_ID) : supported;
        RouteTemplateResolver routeTemplates = templates == null ? RouteTemplateResolver.empty() : templates;

        Map<String, SqlRequestEvidence> byTrace = new HashMap<>();
        Set<String> ambiguousTraces = new HashSet<>();
        for (SqlRequestEvidence request : candidates) {
            String trace = blankToNull(request.traceId());
            if (trace != null && byTrace.putIfAbsent(trace, request) != null) {
                ambiguousTraces.add(trace);
            }
        }

        Map<String, RouteAccumulator> routes = new LinkedHashMap<>();
        BucketAccumulator unattributed = new BucketAccumulator();
        BucketAccumulator ambiguous = new BucketAccumulator();
        long attributed = 0;

        for (SqlTraceEntryDto entry : executions) {
            Match match = match(entry, candidates, tiers, byTrace, ambiguousTraces);
            if (match.request() == null) {
                (match.ambiguous() ? ambiguous : unattributed).add(entry);
                continue;
            }
            attributed++;
            RouteKey key = RouteKey.of(match.request(), routeTemplates);
            routes.computeIfAbsent(key.id(), id -> new RouteAccumulator(key)).add(entry, match);
        }

        List<RouteAccumulator> ordered = routes.values().stream()
                .sorted(Comparator.comparingLong(RouteAccumulator::totalDurationMicros)
                        .reversed()
                        .thenComparing(accumulator -> accumulator.key().id()))
                .toList();
        List<SqlRouteRankingDto> ranked = ordered.stream()
                .limit(MAX_ROUTES)
                .map(accumulator -> accumulator.toDto(totalRetainedDurationMicros))
                .toList();

        return new SqlRouteAttributionDto(
                true,
                null,
                tiers.stream().map(Enum::name).sorted().toList(),
                candidates.size(),
                ranked,
                ordered.size() > ranked.size(),
                ordered.size(),
                attributed,
                unattributed.toDto(
                        totalRetainedDurationMicros,
                        "No captured request could have issued these statements: none was in flight for the "
                                + "whole execution, or the request whose trace id the statement carries is no "
                                + "longer retained. Background jobs, scheduled work, startup and schema "
                                + "migrations belong here."),
                ambiguous.toDto(
                        totalRetainedDurationMicros,
                        "More than one captured request was an equally plausible source, so BootUI refused "
                                + "to pick one. Concurrent identical requests and a reused inbound trace id "
                                + "both produce this."),
                notes(executions, candidates, tiers, routeTemplates));
    }

    /** The outcome of correlating one execution: a decided request, an ambiguity, or nothing. */
    private record Match(SqlRequestEvidence request, Correlation correlation, boolean ambiguous) {
        static final Match NONE = new Match(null, null, false);
        static final Match AMBIGUOUS = new Match(null, null, true);

        static Match of(SqlRequestEvidence request, Correlation correlation) {
            return new Match(request, correlation, false);
        }
    }

    private static Match match(
            SqlTraceEntryDto entry,
            List<SqlRequestEvidence> candidates,
            Set<Correlation> tiers,
            Map<String, SqlRequestEvidence> byTrace,
            Set<String> ambiguousTraces) {
        String trace = blankToNull(entry.traceId());
        if (trace != null) {
            if (ambiguousTraces.contains(trace)) {
                return Match.AMBIGUOUS;
            }
            SqlRequestEvidence request = byTrace.get(trace);
            if (request != null) {
                return Match.of(request, Correlation.TRACE_ID);
            }
            // The execution names a request BootUI no longer holds. Falling through to thread or window
            // evidence would hand it to a request that is provably a different trace, so it stays
            // unattributed instead.
            return Match.NONE;
        }

        if (tiers.contains(Correlation.SERVING_THREAD) && blankToNull(entry.thread()) != null) {
            SqlRequestEvidence unique = unique(
                    candidates,
                    candidate -> entry.thread().equals(candidate.thread()) && withinWindow(entry, candidate));
            if (unique == AMBIGUOUS_MARKER) {
                return Match.AMBIGUOUS;
            }
            if (unique != null) {
                return Match.of(unique, Correlation.SERVING_THREAD);
            }
        }

        if (tiers.contains(Correlation.TIME_WINDOW)) {
            SqlRequestEvidence unique = unique(candidates, candidate -> withinWindow(entry, candidate));
            if (unique == AMBIGUOUS_MARKER) {
                return Match.AMBIGUOUS;
            }
            if (unique != null) {
                return Match.of(unique, Correlation.TIME_WINDOW);
            }
        }
        return Match.NONE;
    }

    /** Sentinel distinguishing "several candidates" from "no candidate" without allocating a wrapper. */
    private static final SqlRequestEvidence AMBIGUOUS_MARKER =
            new SqlRequestEvidence(null, null, null, null, null, 0, 0, null);

    private static SqlRequestEvidence unique(
            List<SqlRequestEvidence> candidates, java.util.function.Predicate<SqlRequestEvidence> predicate) {
        SqlRequestEvidence found = null;
        for (SqlRequestEvidence candidate : candidates) {
            if (!predicate.test(candidate)) {
                continue;
            }
            if (found != null) {
                return AMBIGUOUS_MARKER;
            }
            found = candidate;
        }
        return found;
    }

    /**
     * Whether an execution's whole interval fits inside a request's window. The captured timestamp is the
     * moment the statement <em>completed</em>, so a slow statement that started before the request existed
     * would pass a completion-instant test while provably belonging to earlier work — exactly the
     * long-running background query this panel is most likely to surface. Both ends are therefore checked.
     */
    private static boolean withinWindow(SqlTraceEntryDto entry, SqlRequestEvidence request) {
        long completed = entry.timestamp();
        long started = completed - Math.max(0, entry.durationMillis());
        return started >= request.startMillis() - WINDOW_SLACK_MS && completed <= request.endMillis() + WINDOW_SLACK_MS;
    }

    private static List<String> notes(
            List<SqlTraceEntryDto> entries,
            List<SqlRequestEvidence> requests,
            Set<Correlation> tiers,
            RouteTemplateResolver templates) {
        List<String> notes = new ArrayList<>();
        if (requests.isEmpty()) {
            notes.add("No captured HTTP requests were available to attribute against, so every retained "
                    + "statement is reported as unattributed.");
        }
        if (!tiers.contains(Correlation.SERVING_THREAD)) {
            notes.add("This runtime has no one-thread-per-request invariant, so serving-thread correlation "
                    + "is not offered; attribution relies on trace context first.");
        }
        if (tiers.contains(Correlation.TIME_WINDOW)) {
            notes.add("Time-window correlation is used only when exactly one captured request was in "
                    + "flight; overlapping candidates are reported as ambiguous instead.");
        }
        if (!entries.isEmpty() && entries.stream().allMatch(entry -> blankToNull(entry.traceId()) == null)) {
            notes.add("No retained statement carried a trace id. Enabling a distributed-tracing "
                    + "integration such as OpenTelemetry makes attribution exact.");
        }
        notes.add("Routes are grouped by the route template the runtime reports or the application "
                + "declares. A path no declared route matches is grouped by a masked path instead, where "
                + "every segment that does not read like a fixed route word is replaced. Query strings are "
                + "never used.");
        if (templates.isEmpty()) {
            notes.add("No declared route mappings were available to match paths against, so masked paths "
                    + "are the only grouping BootUI can offer here.");
        }
        return notes;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The grouping identity of a route: its method plus its template or masked path. */
    private record RouteKey(String id, String method, String route, String source) {

        static RouteKey of(SqlRequestEvidence request, RouteTemplateResolver templates) {
            String method = request.method() == null || request.method().isBlank()
                    ? "UNKNOWN"
                    : request.method().toUpperCase(java.util.Locale.ROOT);
            String reported =
                    request.routeTemplate() == null || request.routeTemplate().isBlank()
                            ? null
                            : request.routeTemplate().trim();
            String template = reported != null ? reported : templates.resolve(request.path());
            String route = template != null ? template : RoutePathMasker.mask(request.path());
            String source = template != null ? "ROUTE_TEMPLATE" : "MASKED_PATH";
            return new RouteKey(method + " " + route, method, route, source);
        }
    }

    /** Accumulates one route's executions, its statement breakdown and how each execution correlated. */
    private static final class RouteAccumulator {

        private final RouteKey key;
        private final Map<String, SqlStatementAggregate> statements = new LinkedHashMap<>();
        private final Set<String> requestIds = new LinkedHashSet<>();
        private final List<Long> entryIds = new ArrayList<>();
        private long executions;
        private long totalDurationMicros;
        private long maxDurationMicros;
        private long errorCount;
        private long traceCorrelated;
        private long threadCorrelated;
        private long timeWindowCorrelated;

        private RouteAccumulator(RouteKey key) {
            this.key = key;
        }

        private void add(SqlTraceEntryDto entry, Match match) {
            long duration = Math.max(0, entry.durationMicros());
            executions++;
            totalDurationMicros += duration;
            maxDurationMicros = Math.max(maxDurationMicros, duration);
            if (!entry.success()) {
                errorCount++;
            }
            if (entryIds.size() < SqlStatementAggregate.MAX_LINKED_ENTRIES) {
                entryIds.add(entry.id());
            }
            if (match.request().id() != null) {
                requestIds.add(match.request().id());
            }
            switch (match.correlation()) {
                case TRACE_ID -> traceCorrelated++;
                case SERVING_THREAD -> threadCorrelated++;
                case TIME_WINDOW -> timeWindowCorrelated++;
            }
            SqlStatementNormalizer.Result normalized = SqlStatementNormalizer.normalize(entry.sql());
            statements
                    .computeIfAbsent(
                            normalized.fingerprint(),
                            fingerprint -> new SqlStatementAggregate(fingerprint, normalized.sql(), entry.category()))
                    .add(entry);
        }

        private RouteKey key() {
            return key;
        }

        private long totalDurationMicros() {
            return totalDurationMicros;
        }

        private SqlRouteRankingDto toDto(long totalRetainedDurationMicros) {
            List<SqlStatementAggregate> ordered = statements.values().stream()
                    .sorted(Comparator.comparingLong(SqlStatementAggregate::totalDurationMicros)
                            .reversed()
                            .thenComparing(SqlStatementAggregate::fingerprint))
                    .toList();
            List<SqlRouteStatementDto> top = ordered.stream()
                    .limit(MAX_STATEMENTS_PER_ROUTE)
                    .map(aggregate -> new SqlRouteStatementDto(
                            aggregate.fingerprint(),
                            aggregate.sql(),
                            aggregate.category(),
                            aggregate.executions(),
                            SqlDurations.millis(aggregate.totalDurationMicros()),
                            SqlDurations.millis(aggregate.maxDurationMicros()),
                            aggregate.errorCount()))
                    .toList();
            return new SqlRouteRankingDto(
                    key.id(),
                    key.method(),
                    key.route(),
                    key.source(),
                    requestIds.size(),
                    executions,
                    SqlDurations.millis(totalDurationMicros),
                    SqlDurations.millis(maxDurationMicros),
                    executions == 0 ? 0 : SqlDurations.millis((double) totalDurationMicros / executions),
                    errorCount,
                    statements.size(),
                    SqlShares.percent(totalDurationMicros, totalRetainedDurationMicros),
                    traceCorrelated,
                    threadCorrelated,
                    timeWindowCorrelated,
                    top,
                    ordered.size() > top.size(),
                    List.copyOf(entryIds));
        }
    }

    /** Accumulates one explicit non-route bucket. */
    private static final class BucketAccumulator {

        private long executions;
        private long totalDurationMicros;
        private long errorCount;

        private void add(SqlTraceEntryDto entry) {
            executions++;
            totalDurationMicros += Math.max(0, entry.durationMicros());
            if (!entry.success()) {
                errorCount++;
            }
        }

        private SqlAttributionBucketDto toDto(long totalRetainedDurationMicros, String reason) {
            return new SqlAttributionBucketDto(
                    executions,
                    SqlDurations.millis(totalDurationMicros),
                    errorCount,
                    SqlShares.percent(totalDurationMicros, totalRetainedDurationMicros),
                    reason);
        }
    }
}
