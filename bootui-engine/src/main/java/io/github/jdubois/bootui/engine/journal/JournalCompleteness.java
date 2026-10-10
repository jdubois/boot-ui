package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate completeness, independent of the journal's evictable evidence window. */
public final class JournalCompleteness {

    public static final String PREFIX = "journal:";
    static final String VERIFIED = PREFIX + "verified";
    static final String CLEARS = PREFIX + "clears";
    static final String CLEAR_AT = PREFIX + "clear-at";
    static final String SOURCE = PREFIX + "source:";
    static final String DROPPED = PREFIX + "dropped:";
    static final String WINDOW_DROPPED = PREFIX + "window-dropped:";
    static final String MISSED = PREFIX + "missed:";
    static final String WINDOW_MISSED = PREFIX + "window-missed:";
    static final String FAILED = PREFIX + "failed:";
    static final String WINDOW_FAILED = PREFIX + "window-failed:";
    static final String CROSSING_ROUTE = PREFIX + "crossing-route:";
    static final String CROSSING_ERRORS = PREFIX + "crossing-errors:";
    static final String CROSSING_EXECUTION = PREFIX + "crossing-execution:";
    static final String CROSSING_OVERFLOW = PREFIX + "crossing-overflow";
    static final String SQL_PROVENANCE = PREFIX + "sql-provenance";
    static final String SQL_EXECUTION_COVERAGE = PREFIX + "sql-execution-coverage";
    static final String SQL_EXECUTION_SOURCE = PREFIX + "sql-execution-source:";
    static final String SQL_PREPARATION_SOURCE = PREFIX + "sql-preparation-source:";
    static final String SQL_PREPARATIONS = PREFIX + "sql-preparations";
    static final String SQL_UNKNOWN = PREFIX + "sql-unknown";
    static final String SQL_WINDOW_PREPARATIONS = PREFIX + "sql-window-preparations";
    static final String SQL_WINDOW_UNKNOWN = PREFIX + "sql-window-unknown";
    static final String SQL_UNSCOPED = PREFIX + "sql-unscoped";
    static final String SQL_WINDOW_UNSCOPED = PREFIX + "sql-window-unscoped";
    static final String SQL_SCOPE_INCOMPLETE = PREFIX + "sql-scope-incomplete";

    private JournalCompleteness() {}

    /** Explicit metadata presence, not a claim that every recorded source is complete. */
    public static boolean verified(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().getOrDefault(VERIFIED, 0L) == 1;
    }

    public static long clears(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().getOrDefault(CLEARS, 0L);
    }

    public static long clearAt(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().getOrDefault(CLEAR_AT, 0L);
    }

    public static long dropped(AggregatesSnapshot snapshot, JournalSource source) {
        return snapshot.overflowed().getOrDefault(DROPPED + source.propertyName(), 0L);
    }

    public static long missed(AggregatesSnapshot snapshot, JournalSource source) {
        return snapshot.overflowed().getOrDefault(MISSED + source.propertyName(), 0L);
    }

    public static long failed(AggregatesSnapshot snapshot, JournalSource source) {
        return snapshot.overflowed().getOrDefault(FAILED + source.propertyName(), 0L);
    }

    public static boolean windowComplete(AggregatesSnapshot snapshot, JournalSource source) {
        return verified(snapshot)
                && snapshot.overflowed().getOrDefault(SOURCE + source.propertyName(), 0L) == 1
                && snapshot.overflowed().getOrDefault(WINDOW_DROPPED + source.propertyName(), 0L) == 0
                && snapshot.overflowed().getOrDefault(WINDOW_MISSED + source.propertyName(), 0L) == 0
                && snapshot.overflowed().getOrDefault(WINDOW_FAILED + source.propertyName(), 0L) == 0;
    }

    public static boolean wholeRunComplete(AggregatesSnapshot snapshot, JournalSource source) {
        return windowComplete(snapshot, source)
                && clears(snapshot) == 0
                && dropped(snapshot, source) == 0
                && missed(snapshot, source) == 0
                && failed(snapshot, source) == 0;
    }

    public static boolean absenceKnown(AggregatesSnapshot snapshot, JournalSource source, JournalSource root) {
        return wholeRunComplete(snapshot, source)
                && wholeRunComplete(snapshot, root)
                && countersComplete(snapshot, source, root)
                && (root == JournalSource.HTTP
                        ? snapshot.run().openRequests() == 0
                        : snapshot.overflowed().getOrDefault(JournalAggregates.OPEN_EXECUTIONS, 0L) == 0);
    }

    public static boolean exceptionSignaturesComplete(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().getOrDefault("exceptionGroups", 0L) == 0
                && snapshot.overflowed().getOrDefault(JournalAggregates.EXCEPTION_GROUP_ATTRIBUTIONS, 0L) == 0
                && snapshot.exceptionGroups().stream()
                        .noneMatch(group -> "Other".equals(group.groupId())
                                || group.routes().containsKey("Other"));
    }

    public static boolean countersComplete(AggregatesSnapshot snapshot, JournalSource source, JournalSource root) {
        return windowComplete(snapshot, source)
                && (source != JournalSource.SQL || sqlExecutionQualified(snapshot))
                && windowComplete(snapshot, root)
                && (root == JournalSource.HTTP
                        ? snapshot.run().unattributedRequests() == 0
                                && snapshot.overflowed().getOrDefault(JournalAggregates.LATE_REQUEST_ATTRIBUTIONS, 0L)
                                        == 0
                        : snapshot.overflowed().getOrDefault("unattributedExecutions", 0L) == 0)
                && (source != JournalSource.AI
                        || snapshot.overflowed().getOrDefault(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L) == 0);
    }

    public static boolean sqlExecutionQualified(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().getOrDefault(SQL_PROVENANCE, 0L) == 1
                && snapshot.overflowed().getOrDefault(SQL_EXECUTION_COVERAGE, 0L) == 1
                && snapshot.overflowed().getOrDefault(SQL_PREPARATIONS, 0L) == 0
                && snapshot.overflowed().getOrDefault(SQL_UNKNOWN, 0L) == 0
                && snapshot.overflowed().getOrDefault(SQL_UNSCOPED, 0L) == 0
                && snapshot.overflowed().getOrDefault(SQL_SCOPE_INCOMPLETE, 0L) == 0
                && !sqlExecutionScope(snapshot).isEmpty();
    }

    /** Only registered traced-JDBC scope, never an inventory of all database access in the application. */
    public static java.util.Set<String> sqlExecutionScope(AggregatesSnapshot snapshot) {
        return snapshot.overflowed().keySet().stream()
                .filter(key -> key.startsWith(SQL_EXECUTION_SOURCE))
                .map(key -> key.substring(SQL_EXECUTION_SOURCE.length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static boolean sqlScopesComparable(AggregatesSnapshot now, AggregatesSnapshot then) {
        return sqlExecutionQualified(now)
                && sqlExecutionQualified(then)
                && sqlExecutionScope(now).equals(sqlExecutionScope(then));
    }

    public static String absenceReason(RuntimeJournal journal, String noun) {
        if (!journal.records(JournalSource.HTTP)) {
            return "The http source is not recorded, so " + noun + " cannot be classified as not exercised.";
        }
        JournalStatus status = journal.status();
        if (journal.clearBoundary().clears() > 0) {
            return "The recording was cleared during this run, so " + noun
                    + " absent from the remaining counts cannot be classified as not exercised.";
        }
        if (status.dropped().getOrDefault(JournalSource.HTTP, 0L) > 0) {
            return "The http source dropped completion events, so " + noun
                    + " absent from its counts cannot be classified as not exercised.";
        }
        return null;
    }

    public static String absenceReason(RuntimeJournal journal, AggregatesSnapshot snapshot, String noun) {
        String reason = absenceReason(journal, noun);
        if (reason != null) {
            return reason;
        }
        return !verified(snapshot) || !windowComplete(snapshot, JournalSource.HTTP)
                ? "HTTP aggregate completeness is unknown, so " + noun + " cannot be classified as not exercised."
                : null;
    }

    /** Named limits only: metadata presence, configured sources and timestamps are not overflow counts. */
    public static boolean limited(AggregatesSnapshot snapshot) {
        return snapshot.run().unattributedRequests() > 0
                || !exceptionSignaturesComplete(snapshot)
                || snapshot.routes().stream()
                        .anyMatch(route -> route.statements().containsKey("Other"))
                || snapshot.executions().stream()
                        .anyMatch(work -> work.stats().statements().containsKey("Other"))
                || List.of(
                                "routes",
                                "statements",
                                "exceptionGroups",
                                "executions",
                                "unattributedExecutions",
                                JournalAggregates.EDGES,
                                JournalAggregates.LATE_REQUEST_ATTRIBUTIONS,
                                JournalAggregates.TRACE_AI_ATTRIBUTIONS,
                                CROSSING_OVERFLOW)
                        .stream()
                        .anyMatch(key -> snapshot.overflowed().getOrDefault(key, 0L) > 0);
    }

    public static JournalSource rootSource(EdgeRef edge) {
        return switch (edge.fromType()) {
            case ROUTE, GRAPHQL_OPERATION -> JournalSource.HTTP;
            case SCHEDULED_JOB -> JournalSource.SCHEDULED;
            case LISTENER ->
                edge.fromKey().startsWith("websocket:") ? JournalSource.WEBSOCKET : JournalSource.MESSAGING;
            default -> null;
        };
    }

    public static JournalSource targetSource(EdgeRef edge) {
        return switch (edge.toType()) {
            case TABLE -> JournalSource.SQL;
            case CACHE -> JournalSource.CACHE;
            case HOST -> JournalSource.REST_CLIENT;
            case AI_MODEL -> JournalSource.AI;
            case EXCEPTION_GROUP -> JournalSource.EXCEPTION;
            case DESTINATION ->
                edge.toKey().startsWith("websocket:") ? JournalSource.WEBSOCKET : JournalSource.MESSAGING;
            case EVENT -> JournalSource.APP_EVENT;
            default -> null;
        };
    }

    public static boolean edgeAbsenceKnown(AggregatesSnapshot snapshot, EdgeRef edge) {
        JournalSource root = rootSource(edge);
        JournalSource target = targetSource(edge);
        return verified(snapshot)
                && (root == null || wholeRunComplete(snapshot, root))
                && (target == null || wholeRunComplete(snapshot, target))
                && snapshot.overflowed().getOrDefault(JournalAggregates.EDGES, 0L) == 0
                && snapshot.run().openRequests() == 0
                && snapshot.overflowed().getOrDefault(JournalAggregates.OPEN_EXECUTIONS, 0L) == 0
                && (root == null || target == null || countersComplete(snapshot, target, root));
    }

    public static boolean edgeCountsKnown(AggregatesSnapshot snapshot, EdgeRef edge) {
        JournalSource root = rootSource(edge);
        JournalSource target = targetSource(edge);
        return verified(snapshot)
                && (root == null || windowComplete(snapshot, root))
                && (target == null || windowComplete(snapshot, target))
                && (root == null || target == null || countersComplete(snapshot, target, root));
    }

    /** Impact keeps positive completions excluded from comparison because they straddled a clear. */
    public static List<RouteStats> impactRoutes(AggregatesSnapshot snapshot) {
        Map<String, RouteStats> routes = new LinkedHashMap<>();
        snapshot.routes().forEach(route -> routes.put(route.route(), route));
        snapshot.overflowed().forEach((key, count) -> {
            if (!key.startsWith(CROSSING_ROUTE)) {
                return;
            }
            String label = key.substring(CROSSING_ROUTE.length());
            RouteStats route = routes.get(label);
            long errors = snapshot.overflowed().getOrDefault(CROSSING_ERRORS + label, 0L);
            List<Long> statuses = route == null
                    ? new ArrayList<>(List.of(0L, 0L, 0L, 0L, 0L))
                    : new ArrayList<>(route.statusClasses());
            statuses.set(4, statuses.get(4) + errors);
            routes.put(
                    label,
                    new RouteStats(
                            label,
                            count + (route == null ? 0 : route.requests()),
                            List.copyOf(statuses),
                            route == null ? new LatencyHistogram() : route.latency(),
                            route == null ? Map.of() : route.childCounts(),
                            route == null ? Map.of() : route.childNanos(),
                            route == null ? Map.of() : route.statements(),
                            route == null ? 0 : route.connectionWaitNanos(),
                            route == null ? JournalAggregates.RouteResources.NONE : route.resources(),
                            route == null ? new LatencyHistogram() : route.warmLatency(),
                            route == null ? 0 : route.cacheMisses(),
                            route == null ? 0 : route.aiTokens(),
                            route == null ? JournalAggregates.RouteAuthorization.NONE : route.authorization(),
                            route == null ? JournalAggregates.RouteOrm.none() : route.orm()));
        });
        return List.copyOf(routes.values());
    }

    static int positiveEntries(Map<String, Long> metadata) {
        return (int) metadata.keySet().stream()
                .filter(key -> key.startsWith(CROSSING_ROUTE) || key.startsWith(CROSSING_EXECUTION))
                .count();
    }

    /** Source-loss and presence metadata is never trimmed; bounded positive ledgers follow the aggregate budget. */
    static Map<String, Long> trim(Map<String, Long> metadata, int limit) {
        Map<String, Long> kept = new LinkedHashMap<>();
        metadata.forEach((key, count) -> {
            if (!key.startsWith(CROSSING_ROUTE)
                    && !key.startsWith(CROSSING_ERRORS)
                    && !key.startsWith(CROSSING_EXECUTION)) {
                kept.put(key, count);
            }
        });
        for (String prefix : List.of(CROSSING_ROUTE, CROSSING_EXECUTION)) {
            metadata.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .forEach(entry -> {
                        kept.put(entry.getKey(), entry.getValue());
                        if (prefix.equals(CROSSING_ROUTE)) {
                            String errors = CROSSING_ERRORS + entry.getKey().substring(prefix.length());
                            if (metadata.containsKey(errors)) {
                                kept.put(errors, metadata.get(errors));
                            }
                        }
                    });
        }
        return kept;
    }
}
