package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeRestartCostDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunRefDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LatencyHistogram;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import io.github.jdubois.bootui.engine.model.RunEdgeDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares the current run with a previous run's summary ({@code docs/PLAN-v2.md} §5.8). On a laptop, warmup and noise
 * dominate latency, while the work identical requests do is stable, so behavior comes first: what each route ran per
 * request, what it ran or raised that it did not before, and the runtime model's new and gone edges. A count shift
 * needs {@value #MIN_REQUESTS} requests on each side; a new item appears at its first occurrence. Latency comes last,
 * labelled noisy: the warm median with {@value #MIN_WARM_SAMPLES} warm samples on each side, the 95th percentile with
 * {@value #MIN_TAIL_SAMPLES}.
 */
public final class RunComparison {

    public static final String COMPARED = "COMPARED";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String NOT_COMPARABLE = "NOT_COMPARABLE";
    public static final String NO_PREVIOUS_RUN = "NO_PREVIOUS_RUN";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    static final int MIN_REQUESTS = 3;
    static final int MIN_WARM_SAMPLES = 10;
    static final int MIN_TAIL_SAMPLES = 60;
    static final double MIN_LATENCY_SHIFT = 0.5;
    static final double MIN_LATENCY_SHIFT_MS = 20;
    static final double MIN_BEAN_SHIFT_MS = 200;
    static final double MIN_BEAN_SHIFT = 0.5;
    static final double MIN_ALLOCATION_SHIFT_BYTES = 256 * 1024;

    private RunComparison() {}

    /**
     * Compares the current run with {@code previous}.
     *
     * @param current the current run
     * @param now the current run's aggregates
     * @param start what the current run recorded when it started, or {@code null}
     * @param previous the run to compare with, or {@code null} when none is kept
     * @param kept the kept runs that can be compared with, newest first
     * @param noPreviousReason why no run is kept, when {@code previous} is {@code null}
     * @param baselineRunId the id of the kept run read from the baseline file, or {@code null}
     */
    public static RuntimeRunComparisonDto compare(
            RunIdentity current,
            AggregatesSnapshot now,
            RunStart start,
            RunSummary previous,
            List<RunSummary.Header> kept,
            String noPreviousReason,
            String baselineRunId) {
        RuntimeRunRefDto currentRef = new RuntimeRunRefDto(
                current.id(),
                current.ordinal(),
                current.startedAtEpochMillis(),
                null,
                now.run().requests(),
                "CURRENT");
        List<RuntimeRunRefDto> runs = new ArrayList<>();
        for (RunSummary.Header header : kept) {
            runs.add(ref(header, baselineRunId));
        }
        if (previous == null) {
            return new RuntimeRunComparisonDto(
                    NO_PREVIOUS_RUN,
                    noPreviousReason,
                    currentRef,
                    null,
                    runs,
                    List.of(),
                    List.of(),
                    List.of(),
                    unavailable("No previous run is kept."),
                    List.of(),
                    List.of());
        }
        RunSummary.Header before = previous.header();
        RuntimeRunRefDto previousRef = ref(before, baselineRunId);
        String run = "run " + before.ordinal();
        List<String> limitations = new ArrayList<>();
        RunStart previousStart = before.runStart();
        if (start != null && previousStart != null) {
            List<String> reasons = start.facts().notComparableReasons(previousStart.facts());
            if (!reasons.isEmpty()) {
                return new RuntimeRunComparisonDto(
                        NOT_COMPARABLE,
                        reasons.get(0),
                        currentRef,
                        previousRef,
                        runs,
                        reasons,
                        List.of(),
                        List.of(),
                        unavailable("The runs are not comparable."),
                        List.of(),
                        start.facts().limitations(previousStart.facts()));
            }
            limitations.addAll(start.facts().limitations(previousStart.facts()));
        } else if (previousStart == null) {
            limitations.add("Run " + before.ordinal()
                    + " recorded no start facts, so whether it ran on the same database, profiles, and cache is"
                    + " unknown.");
        } else {
            limitations.add("This run has not recorded its start yet, so whether it is comparable is unknown.");
        }
        if (before.omittedEntries() > 0) {
            limitations.add("Run " + before.ordinal() + "'s summary left out its " + before.omittedEntries()
                    + " least-used entries to stay within its size, so something reported as new may have been"
                    + " among them.");
        }

        AggregatesSnapshot then = previous.aggregates();
        Map<String, RouteStats> previousRoutes = new HashMap<>();
        then.routes().forEach(route -> previousRoutes.put(route.route(), route));
        Map<String, Map<String, String>> previousSignatures = signatures(then);
        Map<String, Map<String, String>> currentSignatures = signatures(now);
        List<RuntimeRunChangeDto> behavior = new ArrayList<>();
        List<RuntimeRunChangeDto> latency = new ArrayList<>();
        int compared = 0;
        int tooFew = 0;
        List<RouteStats> routes = new ArrayList<>(now.routes());
        routes.sort(Comparator.comparingLong(RouteStats::requests).reversed().thenComparing(RouteStats::route));
        for (RouteStats route : routes) {
            String name = route.route();
            RouteStats old = previousRoutes.get(name);
            if (old == null) {
                behavior.add(change(
                        "route-new",
                        name,
                        null,
                        "ADDED",
                        null,
                        (double) route.requests(),
                        0,
                        route.requests(),
                        "`" + name + "` served " + route.requests() + plural(" request", route.requests())
                                + ", and none in " + run + "."));
                continue;
            }
            for (Map.Entry<String, Long> statement : route.statements().entrySet()) {
                String fingerprint = statement.getKey();
                if (!"Other".equals(fingerprint) && !old.statements().containsKey(fingerprint)) {
                    behavior.add(change(
                            "new-statement",
                            name,
                            fingerprint,
                            "ADDED",
                            null,
                            (double) statement.getValue(),
                            old.requests(),
                            route.requests(),
                            "`" + name + "` ran `" + fingerprint + "` " + times(statement.getValue())
                                    + ", which its " + old.requests() + plural(" request", old.requests()) + " in "
                                    + run + " never ran."));
                }
            }
            Map<String, String> raised = currentSignatures.getOrDefault(name, Map.of());
            Map<String, String> raisedBefore = previousSignatures.getOrDefault(name, Map.of());
            raised.forEach((signature, exceptionClass) -> {
                if (!raisedBefore.containsKey(signature)) {
                    behavior.add(change(
                            "new-exception",
                            name,
                            exceptionClass,
                            "ADDED",
                            null,
                            null,
                            old.requests(),
                            route.requests(),
                            "`" + name + "` raised `" + exceptionClass + "`, which its " + old.requests()
                                    + plural(" request", old.requests()) + " in " + run + " never raised."));
                }
            });
            if (route.requests() < MIN_REQUESTS || old.requests() < MIN_REQUESTS) {
                tooFew++;
                continue;
            }
            compared++;
            perRequest(
                    behavior,
                    "statements-per-request",
                    "statements",
                    name,
                    run,
                    old,
                    route,
                    child(old, JournalSource.SQL),
                    child(route, JournalSource.SQL));
            perRequest(
                    behavior,
                    "rest-calls-per-request",
                    "REST calls",
                    name,
                    run,
                    old,
                    route,
                    child(old, JournalSource.REST_CLIENT),
                    child(route, JournalSource.REST_CLIENT));
            perRequest(
                    behavior,
                    "ai-calls-per-request",
                    "AI calls",
                    name,
                    run,
                    old,
                    route,
                    child(old, JournalSource.AI),
                    child(route, JournalSource.AI));
            perRequest(
                    behavior,
                    "cache-misses-per-request",
                    "cache misses",
                    name,
                    run,
                    old,
                    route,
                    old.cacheMisses(),
                    route.cacheMisses());
            tokens(behavior, name, run, old, route);
            statusShare(behavior, name, run, old, route, 3, "4xx");
            statusShare(behavior, name, run, old, route, 4, "5xx");
            allocation(behavior, name, run, old, route);
            latency(latency, "warm-p50", 50, MIN_WARM_SAMPLES, name, run, old.warmLatency(), route.warmLatency());
            latency(latency, "warm-p95", 95, MIN_TAIL_SAMPLES, name, run, old.warmLatency(), route.warmLatency());
        }

        RunEdgeDiff diff = RunEdgeDiff.compare(previous, now, null);
        List<RuntimeRunChangeDto> edges = new ArrayList<>();
        diff.added().forEach(edge -> edges.add(edge(edge, true, run)));
        diff.removed().forEach(edge -> edges.add(edge(edge, false, run)));
        limitations.addAll(diff.limitations());

        if (tooFew > 0) {
            limitations.add(tooFew + plural(" route", tooFew) + " served fewer than " + MIN_REQUESTS
                    + " requests in one of the runs, so only what is new on " + (tooFew == 1 ? "it" : "them")
                    + " is compared.");
        }
        latency.sort(Comparator.comparing(RuntimeRunChangeDto::kind));
        String status = compared > 0 ? COMPARED : INSUFFICIENT;
        String reason = compared > 0
                ? null
                : "No route served at least " + MIN_REQUESTS + " requests in both runs, so a route that did not"
                        + " change cannot be told from one that was not exercised enough.";
        return new RuntimeRunComparisonDto(
                status,
                reason,
                currentRef,
                previousRef,
                runs,
                List.of(),
                capped(behavior, "behavior rows", limitations),
                capped(edges, "edges", limitations),
                restartCost(current, start, before),
                capped(latency, "latency rows", limitations),
                limitations);
    }

    private static RuntimeRunRefDto ref(RunSummary.Header header, String baselineRunId) {
        return new RuntimeRunRefDto(
                header.runId(),
                header.ordinal(),
                header.startedAtEpochMillis(),
                header.endedAtEpochMillis(),
                header.requests(),
                header.runId().equals(baselineRunId) ? "BASELINE_FILE" : "MEMORY");
    }

    /** Each route's exception signatures, or group ids when a group has no signature, with their exception class. */
    private static Map<String, Map<String, String>> signatures(AggregatesSnapshot aggregates) {
        Map<String, Map<String, String>> byRoute = new HashMap<>();
        for (ExceptionGroupStats group : aggregates.exceptionGroups()) {
            String key = group.signature() != null ? group.signature() : group.groupId();
            String exceptionClass = group.exceptionClass() == null ? key : simpleName(group.exceptionClass());
            for (String route : group.routes().keySet()) {
                byRoute.computeIfAbsent(route, ignored -> new LinkedHashMap<>()).putIfAbsent(key, exceptionClass);
            }
        }
        return byRoute;
    }

    private static long child(RouteStats route, JournalSource source) {
        return route.childCounts().getOrDefault(source, 0L);
    }

    /** A per-request count that moved by at least half a unit and a tenth. */
    private static void perRequest(
            List<RuntimeRunChangeDto> rows,
            String kind,
            String noun,
            String route,
            String run,
            RouteStats old,
            RouteStats current,
            long before,
            long after) {
        double then = (double) before / old.requests();
        double now = (double) after / current.requests();
        double shift = Math.abs(now - then);
        if (shift < 0.5 || shift < 0.1 * Math.max(then, now)) {
            return;
        }
        rows.add(change(
                kind,
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                old.requests(),
                current.requests(),
                "`" + route + "` ran " + decimal(now) + " " + noun + " per request, " + (now > then ? "up" : "down")
                        + " from " + decimal(then) + " in " + run + " (" + current.requests() + " and "
                        + old.requests() + " requests)."));
    }

    private static void tokens(
            List<RuntimeRunChangeDto> rows, String route, String run, RouteStats old, RouteStats current) {
        double then = (double) old.aiTokens() / old.requests();
        double now = (double) current.aiTokens() / current.requests();
        double shift = Math.abs(now - then);
        if (shift < 100 || shift < 0.2 * Math.max(then, now)) {
            return;
        }
        rows.add(change(
                "tokens-per-request",
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                old.requests(),
                current.requests(),
                "`" + route + "` used " + Math.round(now) + " model tokens per request, " + (now > then ? "up" : "down")
                        + " from " + Math.round(then) + " in " + run + "."));
    }

    /** A status class's share of a route's responses that moved by at least ten points. */
    private static void statusShare(
            List<RuntimeRunChangeDto> rows,
            String route,
            String run,
            RouteStats old,
            RouteStats current,
            int statusClass,
            String label) {
        double then = 100.0 * old.statusClasses().get(statusClass) / old.requests();
        double now = 100.0 * current.statusClasses().get(statusClass) / current.requests();
        if (Math.abs(now - then) < 10) {
            return;
        }
        rows.add(change(
                "status-" + label,
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                old.requests(),
                current.requests(),
                "`" + route + "` answered " + Math.round(now) + " % of its requests with " + label + ", "
                        + (now > then ? "up" : "down") + " from " + Math.round(then) + " % in " + run + "."));
    }

    private static void allocation(
            List<RuntimeRunChangeDto> rows, String route, String run, RouteStats old, RouteStats current) {
        long thenMeasured = old.resources().measuredRequests();
        long nowMeasured = current.resources().measuredRequests();
        if (thenMeasured < MIN_REQUESTS || nowMeasured < MIN_REQUESTS) {
            return;
        }
        double then = (double) old.resources().allocatedBytes() / thenMeasured;
        double now = (double) current.resources().allocatedBytes() / nowMeasured;
        double shift = Math.abs(now - then);
        if (shift < MIN_ALLOCATION_SHIFT_BYTES || shift < 0.5 * Math.max(then, now)) {
            return;
        }
        rows.add(change(
                "allocation-per-request",
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                thenMeasured,
                nowMeasured,
                "`" + route + "` allocated " + megabytes(now) + " per measured request, " + (now > then ? "up" : "down")
                        + " from " + megabytes(then) + " in " + run + "."));
    }

    private static void latency(
            List<RuntimeRunChangeDto> rows,
            String kind,
            int percentile,
            int minSamples,
            String route,
            String run,
            LatencyHistogram old,
            LatencyHistogram current) {
        if (old.count() < minSamples || current.count() < minSamples) {
            return;
        }
        Long thenMicros = old.percentileMicros(percentile);
        Long nowMicros = current.percentileMicros(percentile);
        if (thenMicros == null || nowMicros == null) {
            return;
        }
        double then = thenMicros / 1000.0;
        double now = nowMicros / 1000.0;
        double shift = Math.abs(now - then);
        if (shift < MIN_LATENCY_SHIFT_MS || shift < MIN_LATENCY_SHIFT * Math.min(then, now)) {
            return;
        }
        rows.add(change(
                kind,
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                old.count(),
                current.count(),
                "`" + route + "`'s warm p" + percentile + " was " + decimal(now) + " ms, "
                        + (now > then ? "up" : "down")
                        + " from " + decimal(then) + " ms in " + run + " (" + current.count() + " and " + old.count()
                        + " warm requests). Latency on a laptop is noisy: confirm before acting on it."));
    }

    private static RuntimeRunChangeDto edge(ObservedEdge observed, boolean added, String run) {
        EdgeRef edge = observed.edge();
        String target = edge.toType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " `" + edge.toKey() + "`";
        String verb = verb(edge.type());
        String sentence = added
                ? "`" + edge.fromKey() + "` " + verb + " " + target + ", " + times(observed.count()) + ", and not in "
                        + run + "."
                : "`" + edge.fromKey() + "` no longer " + verb + " " + target + ", which it did "
                        + times(observed.count()) + " in " + run + ".";
        return change(
                "edge",
                edge.fromKey(),
                edge.type().name() + " " + edge.toType().name() + " " + edge.toKey(),
                added ? "ADDED" : "REMOVED",
                added ? null : (double) observed.count(),
                added ? (double) observed.count() : null,
                added ? 0 : observed.count(),
                added ? observed.count() : 0,
                sentence);
    }

    private static String verb(EdgeType type) {
        return switch (type) {
            case READS -> "reads";
            case WRITES -> "writes";
            case CALLS -> "calls";
            case PUBLISHES -> "publishes to";
            case CONSUMES -> "consumes";
            case RAISES -> "raises";
            case DEPENDS_ON -> "depends on";
            case HANDLED_BY -> "is handled by";
        };
    }

    /** Restart cost: compared only between two restarts, never with a cold start. */
    private static RuntimeRestartCostDto restartCost(RunIdentity current, RunStart start, RunSummary.Header before) {
        RunStart previous = before.runStart();
        if (start == null || previous == null) {
            return unavailable("A run recorded no start facts.");
        }
        if (start.readyNanos() == null || previous.readyNanos() == null) {
            return unavailable("The framework reports no time to ready, as on Quarkus, where only the live-reload"
                    + " total is logged.");
        }
        if (current.ordinal() <= 1 || before.ordinal() <= 1) {
            return unavailable("A restart is compared only with the previous restart, never with a cold start, which"
                    + " also loads the JVM and its classes.");
        }
        Map<String, StartupStepTiming> previousBeans = new HashMap<>();
        for (StartupStepTiming step : previous.slowestSteps()) {
            if (step.bean() != null) {
                previousBeans.put(step.bean(), step);
            }
        }
        List<RuntimeRunChangeDto> beans = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StartupStepTiming step : start.slowestSteps()) {
            StartupStepTiming old = step.bean() == null ? null : previousBeans.get(step.bean());
            if (old == null || !seen.add(step.bean())) {
                continue;
            }
            double then = old.durationNanos() / 1_000_000.0;
            double now = step.durationNanos() / 1_000_000.0;
            double shift = Math.abs(now - then);
            if (shift < MIN_BEAN_SHIFT_MS || shift < MIN_BEAN_SHIFT * then) {
                continue;
            }
            beans.add(change(
                    "bean-initialization",
                    step.bean(),
                    null,
                    now > then ? "INCREASED" : "DECREASED",
                    then,
                    now,
                    1,
                    1,
                    "Bean `" + step.bean() + "` took " + Math.round(now) + " ms to initialize, "
                            + (now > then ? "up" : "down") + " from " + Math.round(then) + " ms in run "
                            + before.ordinal() + "."));
        }
        beans.sort(
                Comparator.comparingDouble((RuntimeRunChangeDto change) -> Math.abs(change.after() - change.before()))
                        .reversed());
        return new RuntimeRestartCostDto(
                COMPARED, null, previous.readyNanos() / 1_000_000.0, start.readyNanos() / 1_000_000.0, beans);
    }

    private static RuntimeRestartCostDto unavailable(String reason) {
        return new RuntimeRestartCostDto(UNAVAILABLE, reason, null, null, List.of());
    }

    private static List<RuntimeRunChangeDto> capped(
            List<RuntimeRunChangeDto> rows, String what, List<String> limitations) {
        if (rows.size() <= RuntimeRunComparisonDto.MAX_ROWS) {
            return rows;
        }
        limitations.add((rows.size() - RuntimeRunComparisonDto.MAX_ROWS) + " more " + what + " are not listed.");
        return rows.subList(0, RuntimeRunComparisonDto.MAX_ROWS);
    }

    private static RuntimeRunChangeDto change(
            String kind,
            String subject,
            String detail,
            String change,
            Double before,
            Double after,
            long beforeSamples,
            long afterSamples,
            String sentence) {
        return new RuntimeRunChangeDto(
                kind, subject, detail, change, before, after, beforeSamples, afterSamples, sentence);
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String times(long count) {
        return count == 1 ? "once" : count + " times";
    }

    private static String plural(String noun, long count) {
        return count == 1 ? noun : noun + "s";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String megabytes(double bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024 * 1024));
    }
}
