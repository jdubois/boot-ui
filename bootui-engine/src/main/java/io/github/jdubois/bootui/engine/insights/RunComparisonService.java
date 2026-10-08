package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.CodeInventoryChangeCountsDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.RuntimeCodeChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeCodeChangesDto;
import io.github.jdubois.bootui.core.dto.RuntimeRestartCostDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangesDto;
import io.github.jdubois.bootui.engine.codepaths.MethodRoutes;
import io.github.jdubois.bootui.engine.codepaths.TracedMethods;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RunSideEffects;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves {@code GET /runtime-insights/comparison} on every stack ({@code docs/PLAN-v2.md} §5.8): the current run
 * compared with the newest kept run, or with a chosen one. It reads the aggregates and the run history in memory: it
 * starts no capture, scan, or network call.
 */
public final class RunComparisonService {
    private static final Logger LOG = Logger.getLogger(RunComparisonService.class.getName());

    /** Why a comparison names no previous run: no kept run has the id {@code runId} the caller asked for. */
    static String unknownRunReason(String runId) {
        return "No kept run has the id " + runId + ": it may have been dropped, as only the " + RunHistory.MAX_RUNS
                + " most recent runs are kept.";
    }

    /**
     * Whether {@code comparison} answers a run id no kept run has, as opposed to a history that is off or unreadable.
     */
    public static boolean unknownRun(RuntimeRunComparisonDto comparison, String runId) {
        return runId != null
                && comparison.previous() == null
                && !RunComparison.UNAVAILABLE.equals(comparison.status())
                && unknownRunReason(runId).equals(comparison.reason());
    }

    static final String NO_PREVIOUS_RUN = "No previous run is kept yet: restart the application, as DevTools or a"
            + " Quarkus live reload does, or set bootui.runtime-journal.baseline-file to keep the last run across a"
            + " full JVM restart.";

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RunHistory history;
    private final Predicate<String> panelEnabled;
    private final Predicate<String> panelAvailable;
    private volatile BooleanSupplier agentAttached = () -> false;
    private volatile Function<Integer, CodeInventoryService.ChangesRead> inventoryChanges;
    private volatile Function<Predicate<String>, MethodRoutes> codePaths;
    private volatile Supplier<AppEventCapture> appEventCapture;
    private volatile Supplier<SideEffectsView> sideEffects;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param history the JVM's run history, usually {@link RunHistory#shared()}
     */
    public RunComparisonService(RuntimeJournal journal, JournalAggregates aggregates, RunHistory history) {
        this(journal, aggregates, history, panel -> true, panel -> true);
    }

    public RunComparisonService(
            RuntimeJournal journal, JournalAggregates aggregates, RunHistory history, Predicate<String> panelEnabled) {
        this(journal, aggregates, history, panelEnabled, panel -> true);
    }

    public RunComparisonService(
            RuntimeJournal journal,
            JournalAggregates aggregates,
            RunHistory history,
            Predicate<String> panelEnabled,
            Predicate<String> panelAvailable) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.history = history;
        this.panelEnabled = Objects.requireNonNull(panelEnabled, "panelEnabled");
        this.panelAvailable = Objects.requireNonNull(panelAvailable, "panelAvailable");
    }

    /**
     * Installs Code Inventory and Code Paths, so the comparison leads with the methods changed since the previous run,
     * whether each ran, and on which routes ({@code docs/PLAN-v2.md} §5.8, §5.17, M5-7a). Each is read once per
     * comparison, under its own panels' read.
     *
     * @param agentAttached whether the BootUI agent is attached: without it, the comparison carries no code changes
     * @param changes the changed and added methods with their access flags, at most the given count, such as
     *     {@code CodeInventoryService.changesWithAccess}
     * @param codePaths the routes whose requests executed methods, such as {@code CodePathsService.methodRoutes}, or
     *     {@code null}
     */
    public void setCodeChanges(
            BooleanSupplier agentAttached,
            Function<Integer, CodeInventoryService.ChangesRead> changes,
            Function<Predicate<String>, MethodRoutes> codePaths) {
        this.agentAttached = agentAttached == null ? () -> false : agentAttached;
        this.inventoryChanges = changes;
        this.codePaths = codePaths;
    }

    /**
     * Installs Side Effects, so the comparison says which hosts, file patterns, processes, and variable names are new or
     * gone outside the JVM ({@code docs/PLAN-v2.md} §5.8, §5.16, M5-7b). Read once per comparison, under its panel's and
     * HTTP Exchanges' read; without the agent ({@link #setCodeChanges}'s {@code agentAttached}), the comparison carries
     * none.
     */
    public void setSideEffects(Supplier<SideEffectsService> sideEffects) {
        setSideEffectsView(
                sideEffects == null
                        ? null
                        : () -> {
                            SideEffectsService service = sideEffects.get();
                            return service == null
                                    ? null
                                    : new SideEffectsView(
                                            service.read(), service.unavailableReason(), service::runSideEffects);
                        });
    }

    /** {@link #setSideEffects} from a view of Side Effects rather than its service. */
    public void setSideEffectsView(Supplier<SideEffectsView> sideEffects) {
        this.sideEffects = sideEffects;
    }

    /**
     * What a comparison reads of Side Effects, once per comparison.
     *
     * @param read its panel's and HTTP Exchanges' visibility now
     * @param unavailableReason why Side Effects records nothing, or {@code null}
     * @param current this run's side effects so far, possibly {@code null}
     */
    public record SideEffectsView(
            AgentEvidence.Read read, String unavailableReason, Supplier<RunSideEffects> current) {}

    /**
     * Installs whether this application's application events are recorded ({@link AppEventCapture}), so a comparison
     * that compares no application event edge says why. Without it, they are assumed recorded.
     */
    public void setAppEventCapture(Supplier<AppEventCapture> appEventCapture) {
        this.appEventCapture = appEventCapture;
    }

    /**
     * Compares the current run with the kept run {@code runId}, or, when it is {@code null} or blank, with the newest
     * kept run, including one with no HTTP traffic.
     */
    public RuntimeRunComparisonDto compare(String runId) {
        RunSummary[] previous = new RunSummary[1];
        RuntimeRunComparisonDto comparison = withUnrecordedAppEvents(compareRuns(runId, previous));
        if (RunComparison.UNAVAILABLE.equals(comparison.status()) && comparison.current() == null) {
            return comparison;
        }
        boolean previousRun = runId == null
                || runId.isBlank()
                || "previous".equals(runId)
                || (!comparison.runs().isEmpty()
                        && comparison.runs().get(0).runId().equals(runId));
        return comparison.withCodeChanges(codeChanges(previousRun)).withSideEffects(sideEffects(previous[0]));
    }

    /**
     * What changed outside the JVM since {@code previous}, from Side Effects ({@code docs/PLAN-v2.md} M5-7b);
     * {@code null} without the agent, and unavailable with the reason when it cannot be compared. Never throws.
     */
    RuntimeSideEffectChangesDto sideEffects(RunSummary previous) {
        Supplier<SideEffectsView> source = sideEffects;
        try {
            if (source == null || !agentAttached.getAsBoolean()) {
                // Without the agent, the comparison is what it was before side effects.
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        try {
            SideEffectsView service = source.get();
            if (service == null) {
                return RuntimeSideEffectChangesDto.unavailable(NO_SIDE_EFFECTS);
            }
            AgentEvidence.Read read = service.read();
            String unavailable = service.unavailableReason();
            if (unavailable == null && (read == null || !read.shown())) {
                unavailable = read == null || read.hiddenReason() == null
                        ? "The Side Effects panel is hidden."
                        : read.hiddenReason();
            }
            if (unavailable != null) {
                return RuntimeSideEffectChangesDto.unavailable(unavailable);
            }
            if (previous == null) {
                return RuntimeSideEffectChangesDto.unavailable(
                        "There is no previous run to compare side effects with.");
            }
            RunSideEffects before = previous.sideEffects();
            if (before == null) {
                return RuntimeSideEffectChangesDto.unavailable(
                        "The previous run kept no side effects: it ran without the BootUI agent's side-effect sensors,"
                                + " or with a BootUI that did not keep them.");
            }
            if (before.unavailableReason() != null) {
                return RuntimeSideEffectChangesDto.unavailable(
                        "The previous run kept no side effects. " + before.unavailableReason());
            }
            RunSideEffects now =
                    service.current() == null ? null : service.current().get();
            if (now == null) {
                return RuntimeSideEffectChangesDto.unavailable(NO_SIDE_EFFECTS);
            }
            if (now.unavailableReason() != null) {
                return RuntimeSideEffectChangesDto.unavailable(now.unavailableReason());
            }
            Set<String> routes = new HashSet<>();
            Set<String> executions = new HashSet<>();
            if (aggregates != null) {
                JournalAggregates.AggregatesSnapshot snapshot = aggregates.snapshot();
                snapshot.routes().forEach(route -> {
                    if (route.requests() > 0) {
                        routes.add(route.route());
                    }
                });
                snapshot.executions().forEach(execution -> {
                    if (execution.stats().requests() > 0) {
                        executions.add(execution.stats().route());
                    }
                });
            }
            Set<String> previousRoutes = new HashSet<>();
            Set<String> previousExecutions = new HashSet<>();
            previous.aggregates().routes().forEach(route -> {
                if (route.requests() > 0) {
                    previousRoutes.add(route.route());
                }
            });
            previous.aggregates().executions().forEach(execution -> {
                if (execution.stats().requests() > 0) {
                    previousExecutions.add(execution.stats().route());
                }
            });
            return SideEffectComparison.compare(
                    before,
                    now,
                    (scope, owner) -> SideEffectComparison.ROUTE.equals(scope)
                            ? previousRoutes.contains(owner)
                            : SideEffectComparison.EXECUTION.equals(scope) && previousExecutions.contains(owner),
                    (scope, owner) -> SideEffectComparison.ROUTE.equals(scope)
                            ? routes.contains(owner)
                            : SideEffectComparison.EXECUTION.equals(scope) && executions.contains(owner),
                    !read.requests());
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "BootUI could not compare the side effects", ex);
            return RuntimeSideEffectChangesDto.unavailable(
                    "Side effects could not be compared: " + ex.getClass().getSimpleName() + ".");
        }
    }

    static final String NO_SIDE_EFFECTS = "This run records no side effects: Side Effects needs one of the BootUI"
            + " agent's side-effect sensors (network, files, processes, environment); see the Java Agent panel.";

    /**
     * {@code comparison}, naming among its limitations why no application event edge is compared when this run's
     * application events are not recorded although the journal records the {@code app-event} source.
     */
    private RuntimeRunComparisonDto withUnrecordedAppEvents(RuntimeRunComparisonDto comparison) {
        Supplier<AppEventCapture> supplier = appEventCapture;
        if (supplier == null
                || comparison.current() == null
                || journal == null
                || !journal.records(JournalSource.APP_EVENT)) {
            return comparison;
        }
        AppEventCapture capture = AppEventCapture.read(supplier);
        if (capture.recorded()) {
            return comparison;
        }
        List<String> limitations =
                new ArrayList<>(comparison.limitations() == null ? List.of() : comparison.limitations());
        limitations.add(capture.reason() + " Its application event edges are not compared.");
        return new RuntimeRunComparisonDto(
                comparison.status(),
                comparison.reason(),
                comparison.current(),
                comparison.previous(),
                comparison.runs(),
                comparison.notComparableReasons(),
                comparison.behavior(),
                comparison.edges(),
                comparison.restartCost(),
                comparison.latency(),
                limitations,
                comparison.codeChanges(),
                comparison.sideEffects());
    }

    /**
     * The methods changed, added, and removed since this application's previous run, which ran, and where; unavailable
     * without the inventory sensor, while the Code Inventory panel is disabled, or for a comparison with an older run.
     * Never throws.
     */
    RuntimeCodeChangesDto codeChanges(boolean previousRun) {
        Function<Integer, CodeInventoryService.ChangesRead> changes = inventoryChanges;
        try {
            if (changes == null || !agentAttached.getAsBoolean()) {
                // Without the agent, the comparison is what it was before code changes.
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        if (!previousRun) {
            return RuntimeCodeChangesDto.unavailable("Code changes are listed against this application's previous run"
                    + " only: compare with the previous run to see them.");
        }
        try {
            CodeInventoryService.ChangesRead read = changes.apply(RuntimeRunComparisonDto.MAX_ROWS);
            CodeInventoryChangesReport report = read == null ? null : read.report();
            if (report == null || !report.available()) {
                return RuntimeCodeChangesDto.unavailable(
                        report == null || report.unavailableReason() == null
                                ? RuntimeRunComparisonDto.NO_CODE_CHANGES
                                : report.unavailableReason());
            }
            CodeInventoryChangeCountsDto counts = report.counts();
            if (counts == null || !counts.previousRun()) {
                return RuntimeCodeChangesDto.unavailable(
                        counts == null || counts.note() == null ? NO_INVENTORY_RUN : counts.note());
            }
            List<CodeInventoryMethodDto> methods = report.changes();
            Set<String> keys = new LinkedHashSet<>();
            methods.forEach(method -> keys.add(method.key()));
            MethodRoutes paths = methodRoutes(keys);
            Map<String, Integer> flags = read.accessFlags();
            Set<String> known = new HashSet<>();
            aggregates.snapshot().routes().forEach(route -> known.add(route.route()));
            List<RuntimeCodeChangeDto> rows = new ArrayList<>();
            for (CodeInventoryMethodDto method : methods) {
                rows.add(row(method, paths, flags == null ? Map.of() : flags, known));
            }
            List<String> limitations = new ArrayList<>();
            limitations.add("Code changes are since this application's previous run in this JVM, compared from its"
                    + " class files without git; a method's routes are those whose requests' own call trees executed"
                    + " it, or the route of the first request that ran it.");
            if (counts.note() != null) {
                limitations.add(counts.note());
            }
            if (counts.removed() != null && counts.removed() > 0) {
                limitations.add(
                        counts.removed() + (counts.removed() == 1 ? " removed method is" : " removed methods are")
                                + " counted, not named: the previous run keeps only its methods' hashes.");
            }
            if (!paths.available()) {
                limitations.add("Routes come from Code Inventory's first requests only: " + paths.unavailableReason());
            } else {
                limitations.addAll(paths.limitations());
            }
            int total = report.page() == null ? rows.size() : report.page().matched();
            return new RuntimeCodeChangesDto(true, null, counts, rows, Math.max(total, rows.size()), limitations);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "BootUI could not read the code changes", ex);
            return RuntimeCodeChangesDto.unavailable(
                    "Code changes could not be read: " + ex.getClass().getSimpleName() + ".");
        }
    }

    private static final String NO_INVENTORY_RUN = "No previous run of this application was kept in this JVM: code"
            + " changes are compared from the next DevTools restart or Quarkus live reload.";

    private RuntimeCodeChangeDto row(
            CodeInventoryMethodDto method, MethodRoutes paths, Map<String, Integer> flags, Set<String> knownRoutes) {
        Map<String, Long> byRoute = paths.routesByKey().getOrDefault(method.key(), Map.of());
        List<String> routes = new ArrayList<>(byRoute.keySet());
        routes.sort(Comparator.comparingLong((String route) -> byRoute.get(route))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
        // The first request's route, when the journal names it as it names the routes.
        if (method.firstRoute() != null
                && knownRoutes.contains(method.firstRoute())
                && !routes.contains(method.firstRoute())) {
            routes.add(method.firstRoute());
        }
        String note = null;
        String status = method.status();
        if (!byRoute.isEmpty() && !CodeInventoryService.GENERATED.equals(status)) {
            // Its call trees, read after its status, saw a request run it since.
            status = CodeInventoryService.EXECUTED;
        }
        boolean ran = CodeInventoryService.EXECUTED.equals(status) || CodeInventoryService.GENERATED.equals(status);
        if (ran && byRoute.isEmpty()) {
            Boolean traced = TracedMethods.traced(
                    method.className(),
                    method.name(),
                    method.descriptor(),
                    flags.getOrDefault(method.key(), -1),
                    paths.beanClasses());
            if (!paths.available()) {
                note = "Call trees are unavailable, so only the first request's route is known: "
                        + paths.unavailableReason();
            } else if (Boolean.FALSE.equals(traced)) {
                note = TracedMethods.NOT_TRACED + ", so only the first request's route is known.";
            } else if (paths.excluded().contains(method.key())) {
                note = "The code-paths sensor adaptively excluded it in this run, so its later calls are in no call"
                        + " tree.";
            } else if (paths.unrouted().getOrDefault(method.key(), 0L) > 0) {
                note = "It ran in request trees without a known route.";
            } else if (routes.isEmpty()) {
                note = "No route's call tree shows it yet: it may have run outside any request (at startup, in a"
                        + " scheduled job, or in executor work the agent did not follow), in a tree still settling,"
                        + " or in a fragment the agent dropped.";
            }
        } else if (ran) {
            boolean partial = false;
            for (String route : byRoute.keySet()) {
                MethodRoutes.RouteEvidence evidence = paths.routes().get(route);
                partial |= evidence != null && evidence.partial();
            }
            if (paths.unrouted().getOrDefault(method.key(), 0L) > 0) {
                note = "It also ran in request trees without a known route.";
            } else if (partial) {
                note = "Some of its routes' call trees are partial, so other routes may have run it too.";
            }
        }
        int total = routes.size();
        return new RuntimeCodeChangeDto(
                method.key(),
                method.className(),
                method.name(),
                method.descriptor(),
                method.change(),
                status,
                method.notTrackedReason(),
                total <= RuntimeCodeChangeDto.MAX_ROUTES ? routes : routes.subList(0, RuntimeCodeChangeDto.MAX_ROUTES),
                total,
                note);
    }

    private MethodRoutes methodRoutes(Set<String> keys) {
        Function<Predicate<String>, MethodRoutes> source = codePaths;
        if (source == null) {
            return MethodRoutes.unavailable("the BootUI agent's code-paths sensor is not available.");
        }
        if (keys.isEmpty()) {
            return MethodRoutes.unavailable("no method changed.");
        }
        try {
            MethodRoutes routes = source.apply(keys::contains);
            return routes == null ? MethodRoutes.unavailable("the BootUI agent is not attached.") : routes;
        } catch (RuntimeException ex) {
            return MethodRoutes.unavailable(
                    MethodRoutes.READ_FAILED + ex.getClass().getSimpleName() + ".");
        }
    }

    private RuntimeRunComparisonDto compareRuns(String runId, RunSummary[] selected) {
        if (journal == null || aggregates == null || !journal.settings().enabled()) {
            return new RuntimeRunComparisonDto(
                    RunComparison.UNAVAILABLE,
                    "The runtime journal is disabled: set bootui.runtime-journal.enabled=true.",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RuntimeRestartCostDto(RunComparison.UNAVAILABLE, "The journal is disabled.", null, null, null),
                    List.of(),
                    List.of());
        }
        String current = journal.run().id();
        List<RunSummary> kept = new ArrayList<>();
        List<RunSummary.Header> headers = new ArrayList<>();
        String reason = NO_PREVIOUS_RUN;
        boolean historyUnavailable = history == null;
        if (history != null) {
            try {
                for (RunSummary summary : history.summaries()) {
                    if (!summary.header().runId().equals(current)) {
                        kept.add(summary);
                        headers.add(summary.header());
                    }
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "BootUI could not read the kept run summaries", ex);
                historyUnavailable = true;
                reason = "The kept runs could not be read: " + ex.getClass().getSimpleName() + ".";
            }
            if (history.unavailableReason() != null) {
                historyUnavailable = true;
                reason = history.unavailableReason() + " Set bootui.runtime-journal.baseline-file to keep the last run"
                        + " in a file instead.";
            } else if (history.baselineRunId() == null && history.baselineNote() != null) {
                reason = history.baselineNote() + " " + NO_PREVIOUS_RUN;
            }
        }
        RunSummary previous = null;
        if (runId == null || runId.isBlank() || "previous".equals(runId)) {
            previous = kept.isEmpty() ? null : kept.get(0);
        } else {
            for (RunSummary summary : kept) {
                if (summary.header().runId().equals(runId)) {
                    previous = summary;
                    break;
                }
            }
            if (previous == null) {
                reason = unknownRunReason(runId);
            }
        }
        if (history == null) {
            reason = "The run history is unavailable; no previous run can be retained.";
        }
        selected[0] = previous;
        return RunComparison.compare(
                journal.run(),
                aggregates.snapshot(),
                aggregates.runStart(),
                previous,
                headers,
                reason,
                history == null ? null : history.baselineRunId(),
                historyUnavailable,
                panelUnavailability());
    }

    private Map<String, String> panelUnavailability() {
        Map<String, String> hidden = new LinkedHashMap<>();
        boolean logged = false;
        for (String panel : JournalSourcePanels.owningPanels()) {
            try {
                boolean enabled = panelEnabled.test(panel);
                if (!enabled) {
                    hidden.put(panel, "disabled");
                } else if (!panelAvailable.test(panel)) {
                    hidden.put(panel, "unavailable");
                }
            } catch (RuntimeException ex) {
                hidden.put(panel, "unavailable");
                if (!logged) {
                    LOG.log(Level.WARNING, "BootUI could not read comparison source-panel policy", ex);
                    logged = true;
                }
            }
        }
        return Map.copyOf(hidden);
    }
}
