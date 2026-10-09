package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeRestartCostDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunRefDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExecutionStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.LatencyHistogram;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.StartupStepTiming;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import io.github.jdubois.bootui.engine.model.RunEdgeDiff;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Compares the current run with a previous run's summary ({@code docs/PLAN-v2.md} §5.8). On a laptop, warmup and noise
 * dominate latency, while the work identical requests do is stable, so behavior comes first: what each route ran per
 * request, what it ran or raised that it did not before, and the runtime model's new and gone edges. A count shift
 * needs {@value #MIN_REQUESTS} requests on each side; a new item appears at its first occurrence. Latency comes last,
 * labelled noisy: the warm median with {@value #MIN_WARM_SAMPLES} warm samples on each side, the 95th percentile with
 * {@value #MIN_TAIL_SAMPLES}.
 */
public final class RunComparison {
    private static final Logger LOG = Logger.getLogger(RunComparison.class.getName());

    public static final String COMPARED = "COMPARED";
    public static final String PARTIAL = "PARTIAL";
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
    private static final Set<JournalSource> COMPARISON_SOURCES = EnumSet.of(
            JournalSource.HTTP,
            JournalSource.SQL,
            JournalSource.EXCEPTION,
            JournalSource.REST_CLIENT,
            JournalSource.AI,
            JournalSource.CACHE,
            JournalSource.ORM,
            JournalSource.RESOURCES,
            JournalSource.SCHEDULED,
            JournalSource.MESSAGING,
            JournalSource.WEBSOCKET,
            JournalSource.APP_EVENT);

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
        return compare(current, now, start, previous, kept, noPreviousReason, baselineRunId, false);
    }

    public static RuntimeRunComparisonDto compare(
            RunIdentity current,
            AggregatesSnapshot now,
            RunStart start,
            RunSummary previous,
            List<RunSummary.Header> kept,
            String noPreviousReason,
            String baselineRunId,
            boolean historyUnavailable) {
        return compare(
                current, now, start, previous, kept, noPreviousReason, baselineRunId, historyUnavailable, Map.of());
    }

    public static RuntimeRunComparisonDto compare(
            RunIdentity current,
            AggregatesSnapshot now,
            RunStart start,
            RunSummary previous,
            List<RunSummary.Header> kept,
            String noPreviousReason,
            String baselineRunId,
            boolean historyUnavailable,
            Map<String, String> hiddenPanels) {
        boolean httpVisible = !hiddenPanels.containsKey(BootUiPanels.HTTP_EXCHANGES);
        List<String> limitations = panelLimitations(now, start, previous, kept, hiddenPanels);
        RuntimeRunRefDto currentRef = new RuntimeRunRefDto(
                current.id(),
                current.ordinal(),
                current.startedAtEpochMillis(),
                null,
                httpVisible ? now.run().requests() : 0,
                "CURRENT");
        List<RuntimeRunRefDto> runs = new ArrayList<>();
        for (RunSummary.Header header : kept) {
            runs.add(ref(header, baselineRunId, httpVisible));
        }
        if (previous == null) {
            return new RuntimeRunComparisonDto(
                    historyUnavailable ? UNAVAILABLE : NO_PREVIOUS_RUN,
                    noPreviousReason,
                    currentRef,
                    null,
                    runs,
                    List.of(),
                    List.of(),
                    List.of(),
                    unavailable("No previous run is kept."),
                    List.of(),
                    limitations);
        }
        RunSummary.Header before = previous.header();
        RuntimeRunRefDto previousRef = ref(before, baselineRunId, httpVisible);
        String run = "run " + before.ordinal();
        RunStart previousStart = before.runStart();
        if (start != null && previousStart != null) {
            List<String> reasons = start.facts().notComparableReasons(previousStart.facts());
            if (!reasons.isEmpty()) {
                limitations.addAll(start.facts().limitations(previousStart.facts()));
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
                        limitations);
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
        Set<JournalSource> sharedSources = sharedSources(start, previousStart, now, then);
        sharedSources.removeIf(source -> !sourceVisible(source, hiddenPanels));
        boolean partial = completenessLimitations(now, "This run", sharedSources, httpVisible, limitations)
                | completenessLimitations(then, "Run " + before.ordinal(), sharedSources, httpVisible, limitations);
        partial |= before.omittedEntries() > 0 || before.omittedEdges() > 0;
        if (start == null || previousStart == null) {
            limitations.add(
                    "Without both runs' source settings, source-specific facts are compared only when both recorded that source.");
        }
        if (!then.executionsRecorded() || !now.executionsRecorded()) {
            limitations.add(
                    "A run summary predates execution aggregates; scheduled jobs and consumed messages are not compared.");
        }
        if (then.routes().stream()
                .anyMatch(route -> route.resources().measuredRequests() > 0
                        && route.resources().allocation() == null)) {
            limitations.add(
                    "The previous summary kept allocation totals but no histogram; median allocation is not compared.");
        }
        if (now.overflowed().getOrDefault("unattributedExecutions", 0L) > 0
                || then.overflowed().getOrDefault("unattributedExecutions", 0L) > 0) {
            limitations.add(
                    "Work whose execution completion was not recorded exceeded its pending bound in a run; per-execution counters omit that work.");
        }
        Map<String, RouteStats> previousRoutes = new HashMap<>();
        if (sharedSources.contains(JournalSource.HTTP)) {
            then.routes().forEach(route -> previousRoutes.put(route.route(), route));
        }
        Map<String, Map<String, String>> previousSignatures = signatures(then);
        Map<String, Map<String, String>> currentSignatures = signatures(now);
        List<RuntimeRunChangeDto> behavior = new ArrayList<>();
        List<RuntimeRunChangeDto> latency = new ArrayList<>();
        int compared = 0;
        int adequate = 0;
        int tooFew = 0;
        // Shared sources use recorded settings, or event presence when start facts are missing (M4-9).
        boolean ormInBoth = sharedSources.contains(JournalSource.ORM);
        if (ormInBoth
                && ((then.run().events().getOrDefault(JournalSource.ORM, 0L) == 0)
                        != (now.run().events().getOrDefault(JournalSource.ORM, 0L) == 0))) {
            limitations.add("A run recorded no ORM session events despite enabling the ORM source. Comparing zero"
                    + " assumes its capture listener was active; an application-provided hibernate.session.events.auto"
                    + " listener can prevent BootUI from recording sessions.");
        }
        List<RouteStats> routes = new ArrayList<>();
        if (sharedSources.contains(JournalSource.HTTP)) {
            routes.addAll(now.routes());
        }
        Set<String> executionNames = new LinkedHashSet<>();
        Map<String, JournalSource> roots = new HashMap<>();
        if (then.executionsRecorded() && now.executionsRecorded()) {
            for (ExecutionStats execution : then.executions()) {
                if (executionVisible(execution, sharedSources, hiddenPanels)) {
                    previousRoutes.put(execution.stats().route(), execution.stats());
                }
            }
            for (ExecutionStats execution : now.executions()) {
                if (executionVisible(execution, sharedSources, hiddenPanels)) {
                    routes.add(execution.stats());
                    executionNames.add(execution.stats().route());
                    roots.put(execution.stats().route(), execution.source());
                }
            }
        }
        routes.sort(Comparator.comparingLong(RouteStats::requests).reversed().thenComparing(RouteStats::route));
        for (RouteStats route : routes) {
            String name = route.route();
            boolean execution = executionNames.contains(name);
            String unit = execution ? "execution" : "request";
            JournalSource root = roots.getOrDefault(name, JournalSource.HTTP);
            if ("Other".equals(name)) {
                continue;
            }
            RouteStats old = previousRoutes.get(name);
            if (old == null) {
                if (JournalCompleteness.windowComplete(now, root)
                        && JournalCompleteness.absenceKnown(then, root, root)
                        && before.omittedEntries() == 0
                        && then.overflowed().getOrDefault(execution ? "executions" : "routes", 0L) == 0) {
                    behavior.add(change(
                            "route-new",
                            name,
                            null,
                            "ADDED",
                            null,
                            (double) route.requests(),
                            0,
                            route.requests(),
                            "`" + name + (execution ? "` completed " : "` served ") + route.requests()
                                    + plural(" " + unit, route.requests()) + ", and none in " + run + "."));
                }
                continue;
            }
            if (route.requests() >= MIN_REQUESTS && old.requests() >= MIN_REQUESTS) {
                adequate++;
            }
            if (!JournalCompleteness.windowComplete(now, root) || !JournalCompleteness.windowComplete(then, root)) {
                continue;
            }
            if (counterComparable(sharedSources, now, then, JournalSource.SQL, root)) {
                Map<String, Long> statements = JournalTextExposure.statementCounts(route.statements());
                Map<String, Long> oldStatements = JournalTextExposure.statementCounts(old.statements());
                for (Map.Entry<String, Long> statement : statements.entrySet()) {
                    String fingerprint = statement.getKey();
                    if (!"Other".equals(fingerprint)
                            && !oldStatements.containsKey("Other")
                            && !oldStatements.containsKey(fingerprint)
                            && JournalCompleteness.absenceKnown(then, JournalSource.SQL, root)
                            && before.omittedEntries() == 0) {
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
                                        + ", which its " + old.requests() + plural(" " + unit, old.requests()) + " in "
                                        + run + " never ran."));
                    }
                }
                if (route.requests() >= MIN_REQUESTS
                        && !statements.containsKey("Other")
                        && !oldStatements.containsKey("Other")
                        && JournalCompleteness.absenceKnown(now, JournalSource.SQL, root)) {
                    oldStatements.forEach((fingerprint, count) -> {
                        if (!statements.containsKey(fingerprint)) {
                            behavior.add(change(
                                    "gone-statement",
                                    name,
                                    fingerprint,
                                    "REMOVED",
                                    (double) count,
                                    null,
                                    old.requests(),
                                    route.requests(),
                                    "`" + name + "` did not run `" + fingerprint + "`, which ran " + times(count)
                                            + " in " + run + "."));
                        }
                    });
                }
            }
            Map<String, String> raised = currentSignatures.getOrDefault(name, Map.of());
            Map<String, String> raisedBefore = previousSignatures.getOrDefault(name, Map.of());
            raised.forEach((signature, exceptionClass) -> {
                if (counterComparable(sharedSources, now, then, JournalSource.EXCEPTION, root)
                        && !raisedBefore.containsKey(signature)
                        && JournalCompleteness.absenceKnown(then, JournalSource.EXCEPTION, root)
                        && JournalCompleteness.exceptionSignaturesComplete(then)
                        && before.omittedEntries() == 0) {
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
                                    + plural(" " + unit, old.requests()) + " in " + run + " never raised."));
                }
            });
            if (route.requests() < MIN_REQUESTS || old.requests() < MIN_REQUESTS) {
                tooFew++;
                continue;
            }
            compared++;
            if (counterComparable(sharedSources, now, then, JournalSource.SQL, root)) {
                perRequest(
                        behavior,
                        "statements-per-request",
                        "statements",
                        name,
                        run,
                        old,
                        route,
                        child(old, JournalSource.SQL),
                        child(route, JournalSource.SQL),
                        unit);
            }
            if (counterComparable(sharedSources, now, then, JournalSource.REST_CLIENT, root)) {
                perRequest(
                        behavior,
                        "rest-calls-per-request",
                        "REST calls",
                        name,
                        run,
                        old,
                        route,
                        child(old, JournalSource.REST_CLIENT),
                        child(route, JournalSource.REST_CLIENT),
                        unit);
            }
            if (counterComparable(sharedSources, now, then, JournalSource.AI, root)) {
                perRequest(
                        behavior,
                        "ai-calls-per-request",
                        "AI calls",
                        name,
                        run,
                        old,
                        route,
                        child(old, JournalSource.AI),
                        child(route, JournalSource.AI),
                        unit);
            }
            if (counterComparable(sharedSources, now, then, JournalSource.CACHE, root)) {
                perRequest(
                        behavior,
                        "cache-misses-per-request",
                        "cache misses",
                        name,
                        run,
                        old,
                        route,
                        old.cacheMisses(),
                        route.cacheMisses(),
                        unit);
            }
            if (counterComparable(sharedSources, now, then, JournalSource.AI, root)) {
                tokens(behavior, name, run, old, route, unit);
            }
            if (ormInBoth && counterComparable(sharedSources, now, then, JournalSource.ORM, root)) {
                perRequest(
                        behavior,
                        "flushes-per-request",
                        "Hibernate flushes",
                        name,
                        run,
                        old,
                        route,
                        old.orm().flushes() + old.orm().autoFlushes(),
                        route.orm().flushes() + route.orm().autoFlushes(),
                        unit);
                entities(behavior, name, run, old, route, unit);
            }
            if (execution) {
                statusShare(behavior, name, run, old, route, 4, "failures", unit);
            } else {
                statusShare(behavior, name, run, old, route, 3, "4xx", unit);
                statusShare(behavior, name, run, old, route, 4, "5xx", unit);
                if (counterComparable(sharedSources, now, then, JournalSource.RESOURCES, root)) {
                    allocation(behavior, name, run, old, route);
                }
            }
            latency(latency, "warm-p50", 50, MIN_WARM_SAMPLES, name, run, old.warmLatency(), route.warmLatency(), unit);
            latency(latency, "warm-p95", 95, MIN_TAIL_SAMPLES, name, run, old.warmLatency(), route.warmLatency(), unit);
        }

        RunEdgeDiff diff = RunEdgeDiff.compare(previous, now, null);
        List<RuntimeRunChangeDto> edges = new ArrayList<>();
        diff.added().stream()
                .filter(edge -> comparableEdge(edge, sharedSources, hiddenPanels))
                .filter(edge -> before.omittedEdges() == 0 && JournalCompleteness.edgeAbsenceKnown(then, edge.edge()))
                .filter(edge -> JournalCompleteness.edgeCountsKnown(now, edge.edge()))
                .forEach(edge -> edges.add(edge(edge, true, run)));
        diff.removed().stream()
                .filter(edge -> comparableEdge(edge, sharedSources, hiddenPanels))
                .filter(edge -> JournalCompleteness.edgeAbsenceKnown(now, edge.edge()))
                .filter(edge -> JournalCompleteness.edgeCountsKnown(then, edge.edge()))
                .forEach(edge -> edges.add(edge(edge, false, run)));
        limitations.addAll(diff.limitations());

        if (tooFew > 0) {
            limitations.add(tooFew + plural(" route or execution", tooFew) + " recorded fewer than " + MIN_REQUESTS
                    + " samples in one of the runs, so per-sample changes on " + (tooFew == 1 ? "it" : "them")
                    + " are not compared; statement removal needs at least " + MIN_REQUESTS + " current samples.");
        }
        latency.sort(Comparator.comparing(RuntimeRunChangeDto::kind));
        boolean policyUnavailable = routes.isEmpty()
                && ((!httpVisible && (!now.routes().isEmpty() || !then.routes().isEmpty()))
                        || hasHiddenExecution(now, hiddenPanels)
                        || hasHiddenExecution(then, hiddenPanels));
        String status = adequate > 0 && partial
                ? PARTIAL
                : compared > 0 ? COMPARED : policyUnavailable ? UNAVAILABLE : INSUFFICIENT;
        String reason = PARTIAL.equals(status)
                ? "Some journal evidence could not be compared; reliable dimensions remain available. Read the limits before treating an empty list as no change."
                : compared > 0
                        ? null
                        : policyUnavailable
                                ? "No routes or executions can be compared because their owning panels are disabled or unavailable."
                                : "No comparable route or execution recorded at least " + MIN_REQUESTS
                                        + " samples in both runs, so"
                                        + " unchanged behavior cannot be told from work that was not exercised enough.";
        behavior.replaceAll(row -> executionNames.contains(row.subject()) ? executionRow(row) : row);
        latency.replaceAll(row -> executionNames.contains(row.subject()) ? executionRow(row) : row);
        return new RuntimeRunComparisonDto(
                status,
                reason,
                currentRef,
                previousRef,
                runs,
                List.of(),
                capped(behavior, "behavior rows", limitations),
                capped(edges, "edges", limitations),
                restartCost(current, start, before, kept, baselineRunId),
                capped(latency, "latency rows", limitations),
                limitations);
    }

    private static boolean counterComparable(
            Set<JournalSource> shared,
            AggregatesSnapshot now,
            AggregatesSnapshot then,
            JournalSource source,
            JournalSource root) {
        return shared.contains(source)
                && JournalCompleteness.countersComplete(now, source, root)
                && JournalCompleteness.countersComplete(then, source, root);
    }

    private static boolean completenessLimitations(
            AggregatesSnapshot snapshot,
            String run,
            Set<JournalSource> shared,
            boolean httpVisible,
            List<String> limitations) {
        if (!JournalCompleteness.verified(snapshot)) {
            limitations.add(run + " kept no journal completeness metadata; completeness is unknown, so missing"
                    + " counts and absent behavior are not compared as zero.");
            return true;
        }
        boolean partial = JournalCompleteness.limited(snapshot);
        if (snapshot.run().openRequests() > 0) {
            partial = true;
            limitations.add(run + " has request children awaiting completion; their absent route behavior cannot"
                    + " yet be compared as whole-run absence.");
        }
        if (snapshot.overflowed().getOrDefault(JournalAggregates.OPEN_EXECUTIONS, 0L) > 0) {
            partial = true;
            limitations.add(run + " has execution children awaiting completion; their absent job or message"
                    + " behavior cannot yet be compared as whole-run absence.");
        }
        if (JournalCompleteness.clears(snapshot) > 0) {
            partial = true;
            limitations.add(run + " was cleared; counters compare only complete executions started after the clear,"
                    + " and remaining evidence cannot prove whole-run absence.");
        }
        Set<JournalSource> relevant = EnumSet.copyOf(COMPARISON_SOURCES);
        relevant.retainAll(shared);
        if (httpVisible) {
            relevant.add(JournalSource.HTTP);
        }
        for (JournalSource source : relevant) {
            long dropped = JournalCompleteness.dropped(snapshot, source);
            long missed = JournalCompleteness.missed(snapshot, source);
            long failed = JournalCompleteness.failed(snapshot, source);
            if (failed > 0) {
                partial = true;
                limitations.add(
                        run + "'s " + source.propertyName()
                                + " aggregation failed for a batch of events; affected dimensions cannot prove complete capture.");
            }
            if (missed > 0) {
                partial = true;
                limitations.add(run + "'s " + source.propertyName() + " source missed " + missed
                        + " events before aggregate registration; affected dimensions cannot prove complete capture.");
            }
            if (dropped > 0) {
                partial = true;
                limitations.add(run + "'s " + source.propertyName() + " source dropped " + dropped
                        + " events; affected counters and whole-run absence are not compared.");
            } else if (missed == 0 && failed == 0 && !JournalCompleteness.windowComplete(snapshot, source)) {
                partial = true;
                limitations.add(run + "'s " + source.propertyName()
                        + " source is not recorded; its missing counts are not compared as zero.");
            }
        }
        if (JournalCompleteness.limited(snapshot)) {
            limitations.add(
                    run
                            + " reached aggregate or attribution limits; affected dimensions cannot prove absence or complete per-unit counts.");
        }
        return partial;
    }

    private static RuntimeRunChangeDto executionRow(RuntimeRunChangeDto row) {
        String kind = row.kind()
                .replace("per-request", "per-execution")
                .replace("route-new", "execution-new")
                .replace("status-failures", "execution-failures");
        return change(
                kind,
                row.subject(),
                row.detail(),
                row.change(),
                row.before(),
                row.after(),
                row.beforeSamples(),
                row.afterSamples(),
                row.sentence());
    }

    private static Set<JournalSource> sharedSources(
            RunStart now, RunStart then, AggregatesSnapshot current, AggregatesSnapshot previous) {
        Set<JournalSource> sources = EnumSet.noneOf(JournalSource.class);
        for (JournalSource source : JournalSource.values()) {
            if (now != null && then != null
                    ? now.facts().journalSources().contains(source.propertyName())
                            && then.facts().journalSources().contains(source.propertyName())
                    : current.run().events().getOrDefault(source, 0L) > 0
                            && previous.run().events().getOrDefault(source, 0L) > 0) {
                sources.add(source);
            }
        }
        return sources;
    }

    private static boolean comparableEdge(
            ObservedEdge observed, Set<JournalSource> sources, Map<String, String> hiddenPanels) {
        EdgeRef edge = observed.edge();
        JournalSource origin = JournalCompleteness.rootSource(edge);
        JournalSource target = JournalCompleteness.targetSource(edge);
        return (origin == null || sources.contains(origin))
                && (target == null || sources.contains(target))
                && (origin != JournalSource.MESSAGING || messagingVisible(edge.fromKey(), hiddenPanels))
                && (target != JournalSource.MESSAGING || messagingVisible(edge.toKey(), hiddenPanels));
    }

    private static boolean sourceVisible(JournalSource source, Map<String, String> hiddenPanels) {
        List<String> panels = JournalSourcePanels.panelsOf(source);
        return panels.isEmpty() || panels.stream().anyMatch(panel -> !hiddenPanels.containsKey(panel));
    }

    private static boolean executionVisible(
            ExecutionStats execution, Set<JournalSource> sources, Map<String, String> hiddenPanels) {
        JournalSource source = execution.source();
        if (source == null || !sources.contains(source) || !sourceVisible(source, hiddenPanels)) {
            return false;
        }
        if (source != JournalSource.MESSAGING) {
            return true;
        }
        String key = messagingKey(execution);
        return key != null && messagingVisible(key, hiddenPanels);
    }

    private static String messagingKey(ExecutionStats execution) {
        String name = execution.stats().route();
        String prefix = JournalSource.MESSAGING.propertyName() + " ";
        return name != null && name.startsWith(prefix) && name.length() > prefix.length()
                ? name.substring(prefix.length())
                : null;
    }

    private static boolean messagingVisible(String key, Map<String, String> hiddenPanels) {
        return !hiddenPanels.containsKey(messagingPanel(key));
    }

    private static boolean hasHiddenExecution(AggregatesSnapshot snapshot, Map<String, String> hiddenPanels) {
        return snapshot.executions().stream()
                .anyMatch(execution -> execution.source() != null
                        && (!sourceVisible(execution.source(), hiddenPanels)
                                || (execution.source() == JournalSource.MESSAGING
                                        && messagingKey(execution) != null
                                        && !messagingVisible(messagingKey(execution), hiddenPanels))));
    }

    private static String messagingPanel(String key) {
        int separator = key.indexOf(':');
        return JournalSourcePanels.messagingPanel(separator < 0 ? null : key.substring(0, separator));
    }

    private static List<String> panelLimitations(
            AggregatesSnapshot now,
            RunStart start,
            RunSummary previous,
            List<RunSummary.Header> kept,
            Map<String, String> hiddenPanels) {
        Set<String> suppressed = new LinkedHashSet<>();
        boolean invalidMessagingIdentity = false;
        if (hiddenPanels.containsKey(BootUiPanels.HTTP_EXCHANGES)) {
            suppressed.add(BootUiPanels.HTTP_EXCHANGES);
        }
        Set<JournalSource> shared = previous == null
                ? EnumSet.noneOf(JournalSource.class)
                : sharedSources(start, previous.header().runStart(), now, previous.aggregates());
        List<AggregatesSnapshot> snapshots = previous == null ? List.of(now) : List.of(now, previous.aggregates());
        for (AggregatesSnapshot snapshot : snapshots) {
            for (JournalSource source : List.of(
                    JournalSource.HTTP,
                    JournalSource.SQL,
                    JournalSource.EXCEPTION,
                    JournalSource.REST_CLIENT,
                    JournalSource.AI,
                    JournalSource.CACHE,
                    JournalSource.ORM,
                    JournalSource.SCHEDULED,
                    JournalSource.WEBSOCKET)) {
                if (snapshot.run().events().getOrDefault(source, 0L) > 0 || shared.contains(source)) {
                    JournalSourcePanels.panelsOf(source).stream()
                            .filter(hiddenPanels::containsKey)
                            .filter(panel -> "disabled".equals(hiddenPanels.get(panel))
                                    || snapshot.run().events().getOrDefault(source, 0L) > 0)
                            .forEach(suppressed::add);
                }
            }
            for (ExecutionStats execution : snapshot.executions()) {
                if (execution.source() == JournalSource.MESSAGING) {
                    String key = messagingKey(execution);
                    if (key == null) {
                        invalidMessagingIdentity = true;
                        continue;
                    }
                    String panel = messagingPanel(key);
                    if (hiddenPanels.containsKey(panel)) {
                        suppressed.add(panel);
                    }
                }
            }
            for (ObservedEdge observed : snapshot.edges()) {
                EdgeRef edge = observed.edge();
                if (edge.fromType() == NodeType.LISTENER && !edge.fromKey().startsWith("websocket:")) {
                    String panel = messagingPanel(edge.fromKey());
                    if (hiddenPanels.containsKey(panel)) {
                        suppressed.add(panel);
                    }
                }
                if (edge.toType() == NodeType.DESTINATION && !edge.toKey().startsWith("websocket:")) {
                    String panel = messagingPanel(edge.toKey());
                    if (hiddenPanels.containsKey(panel)) {
                        suppressed.add(panel);
                    }
                }
            }
        }
        if (hiddenPanels.containsKey(BootUiPanels.HTTP_EXCHANGES)
                && kept.stream().anyMatch(header -> header.requests() > 0)) {
            suppressed.add(BootUiPanels.HTTP_EXCHANGES);
        }
        List<String> limitations = new ArrayList<>(suppressed.stream()
                .map(panel -> "Facts are not compared because " + panel + " is " + hiddenPanels.get(panel) + ".")
                .toList());
        if (invalidMessagingIdentity) {
            LOG.warning("BootUI ignored a malformed messaging execution identity in a run comparison.");
            limitations.add("A messaging execution has an invalid identity and is not compared.");
        }
        return limitations;
    }

    private static RuntimeRunRefDto ref(RunSummary.Header header, String baselineRunId, boolean httpVisible) {
        return new RuntimeRunRefDto(
                header.runId(),
                header.ordinal(),
                header.startedAtEpochMillis(),
                header.endedAtEpochMillis(),
                httpVisible ? header.requests() : 0,
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
            long after,
            String unit) {
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
                "`" + route + "` ran " + decimal(now) + " " + noun + " per " + unit + ", "
                        + (now > then ? "up" : "down")
                        + " from " + decimal(then) + " in " + run + " (" + current.requests() + " and "
                        + old.requests() + " " + unit + "s)."));
    }

    /** Entities in the persistence context per request that flushed, a shift of at least 20 and a fifth (M4-9). */
    private static void entities(
            List<RuntimeRunChangeDto> rows, String route, String run, RouteStats old, RouteStats current, String unit) {
        if (old.orm().entityRequests() == 0 || current.orm().entityRequests() == 0) {
            return;
        }
        double then = (double) old.orm().entities() / old.orm().entityRequests();
        double now = (double) current.orm().entities() / current.orm().entityRequests();
        double shift = Math.abs(now - then);
        if (shift < 20 || shift < 0.2 * Math.max(then, now)) {
            return;
        }
        rows.add(change(
                "entities-per-request",
                route,
                null,
                now > then ? "INCREASED" : "DECREASED",
                then,
                now,
                old.orm().entityRequests(),
                current.orm().entityRequests(),
                "`" + route + "` held " + Math.round(now) + " entities in its persistence context per " + unit + ", "
                        + (now > then ? "up" : "down") + " from " + Math.round(then) + " in " + run + "."));
    }

    private static void tokens(
            List<RuntimeRunChangeDto> rows, String route, String run, RouteStats old, RouteStats current, String unit) {
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
                "`" + route + "` used " + Math.round(now) + " model tokens per " + unit + ", "
                        + (now > then ? "up" : "down") + " from " + Math.round(then) + " in " + run + "."));
    }

    /** A status class's share of a route's responses that moved by at least ten points. */
    private static void statusShare(
            List<RuntimeRunChangeDto> rows,
            String route,
            String run,
            RouteStats old,
            RouteStats current,
            int statusClass,
            String label,
            String unit) {
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
                "`" + route + ("execution".equals(unit) ? "` completed " : "` answered ") + Math.round(now)
                        + " % of its " + unit + "s with " + ("execution".equals(unit) ? "a failure" : label) + ", "
                        + (now > then ? "up" : "down") + " from " + Math.round(then) + " % in " + run + "."));
    }

    private static void allocation(
            List<RuntimeRunChangeDto> rows, String route, String run, RouteStats old, RouteStats current) {
        long thenMeasured = old.resources().measuredRequests();
        long nowMeasured = current.resources().measuredRequests();
        if (thenMeasured < MIN_REQUESTS || nowMeasured < MIN_REQUESTS) {
            return;
        }
        LatencyHistogram previous = old.resources().allocation();
        LatencyHistogram present = current.resources().allocation();
        if (previous == null || present == null || previous.count() < MIN_REQUESTS || present.count() < MIN_REQUESTS) {
            return;
        }
        double then = previous.percentileValue(50);
        double now = present.percentileValue(50);
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
                "`" + route + "`'s median allocation was " + megabytes(now) + " per measured request, "
                        + (now > then ? "up" : "down") + " from " + megabytes(then) + " in " + run + "."));
    }

    private static void latency(
            List<RuntimeRunChangeDto> rows,
            String kind,
            int percentile,
            int minSamples,
            String route,
            String run,
            LatencyHistogram old,
            LatencyHistogram current,
            String unit) {
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
                        + " warm " + unit + "s). Latency on a laptop is noisy: confirm before acting on it."));
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
            case INVOKES -> "invokes";
            case OPENS -> "opens";
        };
    }

    /** Restart cost: compared only between two restarts, never with a cold start. */
    private static RuntimeRestartCostDto restartCost(
            RunIdentity current,
            RunStart start,
            RunSummary.Header before,
            List<RunSummary.Header> kept,
            String baselineRunId) {
        if (before.runId().equals(baselineRunId)) {
            return unavailable(
                    "A baseline file may come from another JVM; restart cost needs adjacent restarts in the same JVM.");
        }
        if (current.ordinal() != before.ordinal() + 1
                || !kept.isEmpty() && !kept.get(0).runId().equals(before.runId())) {
            return unavailable(
                    "The selected run is not the immediately preceding restart; restart cost compares adjacent restarts only.");
        }
        RunStart previous = before.runStart();
        if (start == null || previous == null) {
            return unavailable("A run recorded no start facts.");
        }
        if (start.readyNanos() == null || previous.readyNanos() == null) {
            return unavailable("A run recorded no time to ready. A lifecycle-ready event alone, such as Quarkus's"
                    + " StartupEvent, supplies neither a complete reload duration nor its start timestamp; BootUI"
                    + " cannot measure the reload total from it.");
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
