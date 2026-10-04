package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsWindowDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.AiCallOwners;
import io.github.jdubois.bootui.engine.journal.ControlMarkers;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.LifecyclePayload;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime Insights ({@code docs/PLAN-v2.md} §5.4, §5.5), shared by every adapter: projects the journal's retained events
 * on read, evaluates every observation, and caches the result until the journal records more.
 *
 * <p>An observation whose source the journal does not record, or whose panel is disabled, is reported not applicable
 * with the reason, never silently skipped. One that reads SQL where this application's SQL cannot be recorded, as over
 * R2DBC, is reported {@code UNAVAILABLE} with the reason. One whose source dropped events reports its findings as
 * {@code PARTIAL}.</p>
 */
public final class RuntimeInsightsService {

    /**
     * How long after the journal's loss horizon a request or an execution may still have lost an event: a messaging or
     * WebSocket anchor's start is derived from its end and duration in whole milliseconds, and a wall clock can be that
     * coarse, so an anchor can read a little later than its first child.
     */
    static final long LOSS_HORIZON_SLACK_MILLIS = 16;

    /**
     * The words of the limitation and check reasons naming work left out because it started before an event the journal
     * evicted or cleared. The agent view matches them rather than the counts.
     */
    public static final String LEFT_OUT_BEFORE_LOSS = "started before events the journal evicted or cleared";

    /** The evidence rows an observation's detail returns at most. */
    public static final int MAX_EVIDENCE_ROWS = 20;

    /** The exemplar request ids an observation names at most. */
    public static final int MAX_EXEMPLARS = 3;

    /** The markers a report's limitation names at most. */
    static final int MAX_NAMED_MARKERS = 3;

    /** The Code Inventory panel's id, whose evidence {@code changed-code-not-executed} reads. */
    static final String CODE_INVENTORY_PANEL = BootUiPanels.CODE_INVENTORY;

    static final String CODE_INVENTORY_DISABLED = "The Code Inventory panel, whose evidence this reads, is disabled.";

    /** Why Runtime Insights is unavailable without the journal, naming the property to set. */
    public static final String DISABLED = "Runtime Insights reads the runtime journal, which is disabled:"
            + " set bootui.runtime-journal.enabled=true.";

    /** Why no route is listed as not exercised when the application's declared routes could not be read. */
    public static final String ROUTE_INVENTORY_UNAVAILABLE = "The application's declared routes could not be read, so"
            + " the routes no request reached are not listed: that does not mean every route was exercised.";

    /** Why no route is listed as not exercised when the runtime journal does not record HTTP requests. */
    public static final String ROUTE_EXERCISE_UNRECORDED = "The runtime journal does not record the http source"
            + " (bootui.runtime-journal.sources), so the routes no request reached are not listed.";

    /**
     * Prefix of the limitation naming retained scheduled runs and consumed messages, which {@code requests} does not
     * count. The agent view matches this prefix rather than the counted totals.
     */
    public static final String NON_HTTP_PREFIX = "Retained non-HTTP executions, which requests does not count:";

    private final RuntimeJournal journal;
    private final Supplier<RouteTemplateResolver> routes;
    private final Predicate<String> panelEnabled;
    private final List<Observation> observations;
    private final InsightsStack stack;
    private final Supplier<List<RunSummary>> runs;
    private Cached cached;
    private volatile Function<String, Integer> poolSizes;
    private volatile ExposurePolicy exposure;
    private volatile Supplier<List<MappingDto>> declaredMappings;
    private volatile Supplier<JournalAggregates.RouteLabels> runRoutes;
    private volatile Supplier<SqlCapture> sqlCapture;
    private volatile Function<String, String> panelUnavailable;
    private volatile LongSupplier codeInventoryFingerprint;
    private volatile LongSupplier codePathsFingerprint;
    private volatile Supplier<AgentEvidence.Read> codePathsReads;
    private volatile Supplier<AgentEvidence.Read> codeInventoryReads;
    // The reads of the projection in progress, set and cleared by current() under this service's lock.
    private AgentEvidence.Read codePathsRead;
    private AgentEvidence.Read codeInventoryRead;
    private String previousRunOf;
    private RunSummary previousRun;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param routes the application's declared routes, or {@code null}
     * @param panelEnabled whether a panel, by its id, is enabled; {@code null} enables every panel
     * @param stack the stack serving the application, or {@code null} when unknown
     * @param runs the summaries of the runs kept in this JVM, newest first, such as
     *     {@code RunHistory.shared()::summaries}, or {@code null}
     */
    public RuntimeInsightsService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Predicate<String> panelEnabled,
            InsightsStack stack,
            Supplier<List<RunSummary>> runs) {
        this(journal, routes, panelEnabled, stack, runs, AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD);
    }

    /**
     * @param aiTokenThreshold the tokens of one model call above which {@code ai-usage-by-route} reports its route
     *     from that call alone
     */
    public RuntimeInsightsService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Predicate<String> panelEnabled,
            InsightsStack stack,
            Supplier<List<RunSummary>> runs,
            long aiTokenThreshold) {
        this(journal, routes, panelEnabled, stack, runs, defaultObservations(aiTokenThreshold));
    }

    RuntimeInsightsService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Predicate<String> panelEnabled,
            InsightsStack stack,
            Supplier<List<RunSummary>> runs,
            List<Observation> observations) {
        this.journal = journal;
        this.routes = routes == null ? RouteTemplateResolver::empty : routes;
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
        this.stack = stack;
        this.runs = runs;
        this.observations = List.copyOf(observations);
    }

    /**
     * Installs the resolver of each application frame's proxy boundaries, which {@code proxy-bypass} reads
     * ({@code docs/PLAN-v2.md} §5.12); without one, it does not apply.
     */
    public synchronized void setProxyBoundaries(ProxyBoundaries boundaries) {
        for (Observation observation : observations) {
            if (observation instanceof ProxyBypass bypass) {
                bypass.setBoundaries(boundaries);
            }
        }
        this.cached = null;
    }

    /**
     * Installs why the BootUI agent does not propagate executor work for this application, {@code null} when it does,
     * which {@code work-after-response} needs ({@code docs/PLAN-v2.md} §5.17), and
     * {@code bootui.agent.executors.max-handoff}; without propagation, that observation does not apply.
     */
    public synchronized void setAgent(
            java.util.function.Supplier<String> propagationUnavailable, java.time.Duration maxHandoff) {
        for (Observation observation : observations) {
            if (observation instanceof WorkAfterResponse work) {
                work.setAgent(propagationUnavailable, maxHandoff);
            }
        }
        this.cached = null;
    }

    /**
     * Installs what Code Inventory reports about this run's changed methods, which {@code changed-code-not-executed}
     * reads ({@code docs/PLAN-v2.md} §5.17), such as {@code CodeInventoryService::changedCode}; without it, or while the
     * Code Inventory panel is disabled, that observation does not apply. A change in what it reports invalidates the
     * cached projection, since the agent's hit flags change without a journal event.
     */
    public synchronized void setCodeInventory(Supplier<CodeInventoryService.ChangedCode> changes) {
        setCodeInventory(changes, null);
    }

    /**
     * {@link #setCodeInventory(Supplier)}, with a cheap fingerprint of what {@code changes} would answer, such as
     * {@code CodeInventoryService::changesFingerprint}: a read compares it to decide whether its cached projection is
     * stale, instead of asking Code Inventory for its changes, which builds its whole view. Without it, the changes are
     * asked for on each read.
     */
    public synchronized void setCodeInventory(
            Supplier<CodeInventoryService.ChangedCode> changes, LongSupplier fingerprint) {
        this.codeInventoryFingerprint = changes == null || fingerprint == null
                ? null
                : () -> fingerprint.getAsLong() * 31 + (codeInventoryVisible() ? 1 : 0);
        for (Observation observation : observations) {
            if (observation instanceof ChangedCodeNotExecuted changed) {
                changed.setChanges(
                        changes == null
                                ? null
                                : () -> {
                                    CodeInventoryService.ChangedCode code = changes.get();
                                    if (code == null || code.unavailableReason() != null) {
                                        // Without the agent, its reason comes first, whatever the panel's state.
                                        return code;
                                    }
                                    return codeInventoryVisible()
                                            ? code
                                            : new CodeInventoryService.ChangedCode(
                                                    CODE_INVENTORY_DISABLED, false, null, List.of(), 0L, null, null);
                                });
            }
        }
        this.cached = null;
    }

    /**
     * Installs what Code Paths says about each route's handler ({@code docs/PLAN-v2.md} §5.14, M5-4b), such as
     * {@code CodePathsService::handlerMethods}, which {@code route-time-breakdown} splits the handler's work by, and a
     * cheap fingerprint of the route trees, such as {@code CodePathsService::routeTreesFingerprint}, since trees settle
     * without a journal event. While the Code Paths panel is disabled, nothing is split.
     */
    public synchronized void setCodePaths(
            java.util.function.Function<String, io.github.jdubois.bootui.engine.codepaths.HandlerMethods> handlers,
            LongSupplier fingerprint) {
        setCodePaths(handlers, fingerprint, null);
    }

    /**
     * Installs Code Paths as {@link #setCodePaths(java.util.function.Function, LongSupplier)} does, with how a
     * code-paths stamp's method id is named, such as {@code CodePathsService::methodKey}, which {@code repeated-selects}
     * names the method that issued a statement with (M5-4c). While the Code Paths panel is disabled, nothing is named.
     */
    public synchronized void setCodePaths(
            java.util.function.Function<String, io.github.jdubois.bootui.engine.codepaths.HandlerMethods> handlers,
            LongSupplier fingerprint,
            java.util.function.IntFunction<String> methodKeys) {
        setCodePaths(handlers, fingerprint, methodKeys, null);
    }

    /**
     * Installs Code Paths as {@link #setCodePaths(java.util.function.Function, LongSupplier,
     * java.util.function.IntFunction)} does, with how the method that issued a route's statements is found from a
     * stamp's method id, such as {@code CodePathsService::issuingMethod}, which looks past an application repository
     * method to the method that called it (M5-4c). While the Code Paths panel is disabled, nothing is named.
     */
    public synchronized void setCodePaths(
            java.util.function.Function<String, io.github.jdubois.bootui.engine.codepaths.HandlerMethods> handlers,
            LongSupplier fingerprint,
            java.util.function.IntFunction<String> methodKeys,
            java.util.function.BiFunction<String, Integer, io.github.jdubois.bootui.engine.codepaths.IssuingMethod>
                    issuingMethods) {
        this.codePathsFingerprint = handlers == null || fingerprint == null
                ? null
                : () -> codePathsVisible() ? fingerprint.getAsLong() * 31 + 1 : 0L;
        for (Observation observation : observations) {
            if (observation instanceof RouteTimeBreakdown breakdown) {
                breakdown.setCodePaths(
                        handlers == null ? null : route -> codePathsVisible() ? handlers.apply(route) : null);
            }
            if (observation instanceof RepeatedSelects repeated) {
                repeated.setMethodKeys(
                        methodKeys == null ? null : id -> codePathsVisible() ? methodKeys.apply(id) : null);
                repeated.setIssuingMethods(
                        issuingMethods == null
                                ? null
                                : (route, id) -> codePathsVisible() ? issuingMethods.apply(route, id) : null);
            }
        }
        this.cached = null;
    }

    private boolean codePathsVisible() {
        AgentEvidence.Read read = codePathsRead;
        if (read != null) {
            // The projection's one read (M5-11).
            return read.shown();
        }
        try {
            return panelEnabled.test(BootUiPanels.CODE_PATHS);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private long codePathsFingerprint() {
        LongSupplier cheap = codePathsFingerprint;
        if (cheap == null) {
            return 0L;
        }
        try {
            return cheap.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * Installs the maximum size of each connection pool, by the data source name its connections carry, which
     * {@code transaction-across-remote-call} uses for its labelled estimate.
     */
    public synchronized void setPoolSizes(Function<String, Integer> poolSizes) {
        this.poolSizes = poolSizes;
        this.cached = null;
    }

    /**
     * Installs why a panel is off in this application, by its id, {@code null} when the user simply disabled it or it
     * is on. An observation whose evidence belongs to an unavailable panel is then reported as such, naming what would
     * make it available — Quarkus security events, say — rather than as a panel someone switched off. The panel's
     * evidence stays out of the projection either way, so an application firing an event a panel cannot serve never
     * sees it leak into a report.
     *
     * <p>A stack whose panel predicate already means "the user enabled it", as the Spring adapter's does, installs
     * nothing and every panel that is off reads as disabled.</p>
     */
    public synchronized void setPanelUnavailable(Function<String, String> panelUnavailable) {
        this.panelUnavailable = panelUnavailable;
        this.cached = null;
    }

    /**
     * Installs whether this application's SQL can be recorded at all ({@link SqlCapture}): without it, an observation
     * that reads the {@code sql} or {@code connection} source reports {@code UNAVAILABLE} with the reason, rather than
     * running over nothing, and one that only optionally reads them names what it cannot count. Without a supplier,
     * statements are assumed recordable.
     */
    public synchronized void setSqlCapture(Supplier<SqlCapture> sqlCapture) {
        this.sqlCapture = sqlCapture;
        this.cached = null;
    }

    /**
     * Installs the live exposure policy that insight sentences and evidence quote recorded log text and request paths
     * under ({@code PLAN-v2} §8). Without one they are quoted as {@link JournalTextExposure#masked()}. The cached
     * projection is keyed by the rule the policy prescribes, so a live change applies to the next read.
     */
    public synchronized void setExposure(ExposurePolicy exposure) {
        this.exposure = exposure;
        this.cached = null;
    }

    /**
     * The stable id of a finding: its kind and a hash of its key, such as {@code repeated-selects:3fa9c0e1b2}, the same
     * across refreshes and restarts, and safe in a URL path whatever its route holds.
     */
    public static String idOf(String kind, String key) {
        return kind + ":" + InsightText.stableHash(key);
    }

    /**
     * Installs the application's declared routes and the route labels of every request this run completed, which
     * together list the routes no request reached. Without them, the report lists none. {@code declaredMappings}
     * answers {@code null}, or throws, when the route inventory cannot be read, which the report then says rather than
     * implying that every route was exercised.
     */
    public synchronized void setDeclaredRoutes(
            Supplier<List<MappingDto>> declaredMappings, Supplier<JournalAggregates.RouteLabels> runRoutes) {
        this.declaredMappings = declaredMappings;
        this.runRoutes = runRoutes;
        this.cached = null;
    }

    /** The observations of 2.0, in report order. */
    public static List<Observation> defaultObservations() {
        return defaultObservations(AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD);
    }

    /** The observations of 2.0, in report order, with {@code ai-usage-by-route}'s token threshold. */
    public static List<Observation> defaultObservations(long aiTokenThreshold) {
        return List.of(
                new RouteTimeBreakdown(),
                new ExceptionHotspots(),
                new ErrorsBehind2xx(),
                new RepeatedSelects(),
                new ConnectionsPerRequest(),
                new SafeMethodDml(),
                new ProxyBypass(),
                new AnonymousDataReach(),
                new AnonymousSuccessOnRestrictedRoute(),
                new SplitTransactionWrites(),
                new TransactionalListenerSkipped(),
                new AfterCommitWrites(),
                new OrmAutoFlush(),
                new LargePersistenceContext(),
                new TransactionAcrossRemoteCall(),
                new LazySqlAfterHandler(),
                new EventLoopBlocking(),
                new GcInflatedLatency(),
                new HeapGrowthAfterGc(),
                new AiUsageByRoute(aiTokenThreshold),
                new FrameworkWarningsByRoute(),
                new WorkAfterResponse(),
                new ChangedCodeNotExecuted());
    }

    /** The current report, projected from the retained events. */
    public synchronized RuntimeInsightsReportDto report() {
        return current().report();
    }

    /** One observation by its stable id, with its evidence. */
    public synchronized RuntimeObservationDetailDto insight(String id) {
        Cached current = current();
        if (!current.report().available()) {
            return new RuntimeObservationDetailDto(
                    false, current.report().unavailableReason(), null, List.of(), List.of(), 0);
        }
        Detail detail = current.details().get(id);
        if (detail == null) {
            return new RuntimeObservationDetailDto(
                    false,
                    "No observation " + id + " in this run's retained events: it may have been evicted or cleared.",
                    null,
                    List.of(),
                    List.of(),
                    0);
        }
        List<List<String>> rows = detail.finding().rows();
        return new RuntimeObservationDetailDto(
                true,
                null,
                detail.observation(),
                detail.finding().columns(),
                rows.subList(0, Math.min(rows.size(), MAX_EVIDENCE_ROWS)).stream()
                        .map(RuntimeObservationRowDto::new)
                        .toList(),
                Math.max(0, rows.size() - MAX_EVIDENCE_ROWS));
    }

    /**
     * {@link #currentUnderReads()} under one read of the panels owning the agent's evidence (M5-11), resolved here and
     * passed to every Code Paths and Code Inventory read of the projection, its cache key included, so a panel toggled
     * while it runs cannot hide one part and serve another.
     */
    private Cached current() {
        codePathsRead = resolve(codePathsReads);
        codeInventoryRead = resolve(codeInventoryReads);
        try {
            return currentUnderReads();
        } finally {
            codePathsRead = null;
            codeInventoryRead = null;
        }
    }

    private static AgentEvidence.Read resolve(Supplier<AgentEvidence.Read> reads) {
        if (reads == null) {
            return null;
        }
        try {
            return reads.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Installs Code Paths ({@code docs/PLAN-v2.md} §5.14) as {@link #setCodePaths(java.util.function.Function,
     * LongSupplier, java.util.function.IntFunction, java.util.function.BiFunction)} does, from the service itself, so
     * every read of one projection is given the same {@linkplain CodePathsService#read() read} of its panels (M5-11).
     */
    public synchronized void setCodePathsService(Supplier<CodePathsService> codePaths) {
        if (codePaths == null) {
            this.codePathsReads = null;
            setCodePaths(null, null, null, null);
            return;
        }
        this.codePathsReads = () -> {
            CodePathsService service = codePaths.get();
            return service == null ? null : service.read();
        };
        setCodePaths(
                route -> {
                    CodePathsService service = codePaths.get();
                    return service == null ? null : service.handlerMethods(codePathsRead(service), route);
                },
                () -> {
                    CodePathsService service = codePaths.get();
                    return service == null ? 0L : service.routeTreesFingerprint(codePathsRead(service));
                },
                id -> {
                    CodePathsService service = codePaths.get();
                    return service == null ? null : service.methodKey(codePathsRead(service), id);
                },
                (route, id) -> {
                    CodePathsService service = codePaths.get();
                    return service == null ? null : service.issuingMethod(codePathsRead(service), route, id);
                });
    }

    /**
     * Installs Code Inventory ({@code docs/PLAN-v2.md} §5.15) as {@link #setCodeInventory(Supplier, LongSupplier)}
     * does, from the service itself, so the changes and their fingerprint are read under the same
     * {@linkplain CodeInventoryService#read() read} of its panels (M5-11).
     */
    public synchronized void setCodeInventoryService(Supplier<CodeInventoryService> codeInventory) {
        if (codeInventory == null) {
            this.codeInventoryReads = null;
            setCodeInventory(null, null);
            return;
        }
        this.codeInventoryReads = () -> {
            CodeInventoryService service = codeInventory.get();
            return service == null ? null : service.read();
        };
        setCodeInventory(
                () -> {
                    CodeInventoryService service = codeInventory.get();
                    return service == null ? null : service.changedCode(codeInventoryRead(service));
                },
                () -> {
                    CodeInventoryService service = codeInventory.get();
                    return service == null ? 0L : service.changesFingerprint(codeInventoryRead(service));
                });
    }

    private AgentEvidence.Read codePathsRead(CodePathsService service) {
        AgentEvidence.Read read = codePathsRead;
        return read != null ? read : service.read();
    }

    private AgentEvidence.Read codeInventoryRead(CodeInventoryService service) {
        AgentEvidence.Read read = codeInventoryRead;
        return read != null ? read : service.read();
    }

    private Cached currentUnderReads() {
        if (journal == null || !journal.settings().enabled()) {
            return new Cached(
                    -1,
                    -1,
                    new PanelVisibility(Map.of(), Map.of()),
                    SqlCapture.capturing(),
                    null,
                    new RuntimeInsightsReportDto(
                            false, DISABLED, null, List.of(), List.of(), List.of(), List.of(), List.of(), 0),
                    Map.of(),
                    0,
                    Map.of(),
                    0);
        }
        JournalStatus status = journal.status();
        long watermark = status.lastSequence();
        long evicted = status.evictedByCount() + status.evictedByBytes();
        // A panel disabled or re-enabled since the last read changes what may be shown, so it invalidates the cache.
        PanelVisibility visibility = panelVisibility();
        SqlCapture capture = sqlCapture();
        // So is a live change of the exposure policy, which changes what recorded text may be quoted (§8).
        JournalTextExposure text = JournalTextExposure.of(exposure);
        // And so is what Code Inventory reports, which the agent's hit flags change without any journal event.
        // And so are the Code Paths route trees, which settle a little after their requests' journal events.
        long inventory = codeInventoryFingerprint() * 1_000_003L + codePathsFingerprint();
        if (cached != null
                && cached.watermark() == watermark
                && cached.evicted() == evicted
                && cached.visibility().equals(visibility)
                && cached.sqlCapture().equals(capture)
                && cached.clears() == status.clears()
                && cached.dropped().equals(status.dropped())
                && text.equals(cached.exposure())
                && cached.inventory() == inventory) {
            return cached;
        }
        cached = project(status, journal.entries(), watermark, evicted, visibility, capture, text)
                .withInventory(inventory);
        return cached;
    }

    private boolean codeInventoryVisible() {
        AgentEvidence.Read read = codeInventoryRead;
        if (read != null) {
            return read.shown();
        }
        try {
            return panelEnabled.test(CODE_INVENTORY_PANEL);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private long codeInventoryFingerprint() {
        LongSupplier cheap = codeInventoryFingerprint;
        if (cheap != null) {
            try {
                return cheap.getAsLong();
            } catch (RuntimeException ex) {
                return 0L;
            }
        }
        for (Observation observation : observations) {
            if (observation instanceof ChangedCodeNotExecuted changed) {
                CodeInventoryService.ChangedCode code = changed.current();
                if (code == null) {
                    return 0L;
                }
                return code.unavailableReason() != null
                        ? code.unavailableReason().hashCode()
                        : code.fingerprint() * 31 + (code.previousRun() ? 1 : 0);
            }
        }
        return 0L;
    }

    private SqlCapture sqlCapture() {
        Supplier<SqlCapture> supplier = sqlCapture;
        if (supplier == null) {
            return SqlCapture.capturing();
        }
        try {
            SqlCapture capture = supplier.get();
            return capture == null ? SqlCapture.capturing() : capture;
        } catch (RuntimeException ex) {
            return SqlCapture.notRecorded("Whether this application's SQL is recorded could not be read ("
                    + ex.getClass().getSimpleName() + "), so no statement is counted.");
        }
    }

    /**
     * Reads every owning panel's state once, so one projection derives its cache key, the events it keeps, and the
     * reason it gives for a source it left out from the same answer. A panel toggled while a projection runs would
     * otherwise let it drop a source's events and then evaluate as though the source were visible, which reads as the
     * source having nothing to report.
     */
    private PanelVisibility panelVisibility() {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        Function<String, String> reasons = panelUnavailable;
        for (String panel : JournalSourcePanels.owningPanels()) {
            boolean on;
            try {
                on = panelEnabled.test(panel);
            } catch (RuntimeException ex) {
                on = false;
            }
            enabled.put(panel, on);
            if (!on && reasons != null) {
                String reason;
                try {
                    reason = reasons.apply(panel);
                } catch (RuntimeException ex) {
                    reason = null;
                }
                if (reason != null && !reason.isBlank()) {
                    unavailable.put(panel, reason.trim());
                }
            }
        }
        return new PanelVisibility(Map.copyOf(enabled), Map.copyOf(unavailable));
    }

    /**
     * {@code entries} without the evidence of a disabled panel ({@code docs/PLAN-v2.md} §8, source-panel policy), so a
     * disabled panel's events are neither counted nor read by an observation.
     *
     * <p>A unit of work is left out whole when the panel owning the event that opens it is disabled: a request, a
     * scheduled run, a consumed message, and a WebSocket handler name their route, destination, and outcome, which is
     * the disabled panel's evidence, and keeping the opening event to carry its children would publish exactly that.
     * Every observation reading the source also reports {@code NOT_APPLICABLE}, naming the panel.
     */
    private VisibleEntries visibleEntries(List<JournalEntry> entries, PanelVisibility visibility, Long lossHorizon) {
        Set<String> hidden = new HashSet<>();
        Set<String> incomplete = new HashSet<>();
        Map<ProjectedRequest.Kind, Integer> incompleteByKind = new EnumMap<>(ProjectedRequest.Kind.class);
        int aiCallsLeftOut = 0;
        Map<ProjectedRequest.Kind, Set<String>> hiddenPanels = new EnumMap<>(ProjectedRequest.Kind.class);
        AiCallOwners aiCallOwners = new AiCallOwners(journal::evictedARequestOf);
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            aiCallOwners.learn(event);
            if (anchorsAUnitOfWork(event) && visibility.visible(event) && beforeLoss(event, lossHorizon)) {
                // It started before an event the journal lost, which may have been its own: judging it would read a
                // missing transaction, cache access, or decision as one that never happened.
                if (incomplete.add(unitOf(event))) {
                    incompleteByKind.merge(kindOf(event), 1, Integer::sum);
                }
            }
            if (anchorsAUnitOfWork(event) && !visibility.visible(event)) {
                hidden.add(unitOf(event));
                ProjectedRequest.Kind kind = kindOf(event);
                hiddenPanels
                        .computeIfAbsent(kind, ignored -> new LinkedHashSet<>())
                        .add(JournalSourcePanels.panelOf(event));
            }
        }
        List<JournalEntry> visible = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (!visibility.visible(event)) {
                continue;
            }
            String unit = unitOf(event);
            if (unit != null && (hidden.contains(unit) || incomplete.contains(unit))) {
                continue;
            }
            if (AiCallOwners.linksByTrace(event)) {
                if (beforeLoss(event, lossHorizon)) {
                    // Its request may be the one whose events were lost, so it is attributed to none.
                    aiCallsLeftOut++;
                    continue;
                }
                String owner = aiCallOwners.ownerOf(event);
                if (owner != null && (hidden.contains("request:" + owner) || incomplete.contains("request:" + owner))) {
                    continue;
                }
            }
            visible.add(entry);
        }
        Map<ProjectedRequest.Kind, List<String>> panelsByKind = new EnumMap<>(ProjectedRequest.Kind.class);
        hiddenPanels.forEach((kind, panels) -> panelsByKind.put(kind, List.copyOf(panels)));
        return new VisibleEntries(visible, Map.copyOf(panelsByKind), Map.copyOf(incompleteByKind), aiCallsLeftOut);
    }

    /** The kind of unit of work an anchoring event opens. */
    private static ProjectedRequest.Kind kindOf(RuntimeEvent event) {
        return event.requestId() != null
                ? ProjectedRequest.Kind.HTTP
                : event.payload() instanceof ScheduledPayload
                        ? ProjectedRequest.Kind.SCHEDULED
                        : ProjectedRequest.Kind.MESSAGE;
    }

    /**
     * The sentence naming the work left out because it started before an event the journal lost, or {@code null} when
     * none was.
     */
    static String leftOutBeforeLoss(int units, int aiCalls) {
        if (units + aiCalls == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (units > 0) {
            parts.add(units == 1 ? "1 request or execution" : units + " requests or executions");
        }
        if (aiCalls > 0) {
            parts.add(aiCalls == 1 ? "1 AI call linked only by a trace" : aiCalls + " AI calls linked only by a trace");
        }
        boolean one = units + aiCalls == 1;
        return String.join(" and ", parts) + " " + LEFT_OUT_BEFORE_LOSS + ", so "
                + (one ? "it is left out: some of its" : "they are left out: some of their")
                + " events may be missing.";
    }

    /** Whether {@code event} started early enough that its unit of work may have lost an event. */
    private static boolean beforeLoss(RuntimeEvent event, Long lossHorizon) {
        return lossHorizon != null && event.epochMillis() <= lossHorizon + LOSS_HORIZON_SLACK_MILLIS;
    }

    private static boolean anchorsAUnitOfWork(RuntimeEvent event) {
        return (event.source() == JournalSource.HTTP && event.requestId() != null)
                || InsightsSnapshot.opensExecution(event);
    }

    /** The request or execution an event belongs to, or {@code null} when it belongs to neither. */
    private static String unitOf(RuntimeEvent event) {
        if (event.requestId() != null) {
            return "request:" + event.requestId();
        }
        return event.executionId() == null ? null : "execution:" + event.executionId();
    }

    private Cached project(
            JournalStatus status,
            List<JournalEntry> entries,
            long watermark,
            long evicted,
            PanelVisibility visibility,
            SqlCapture capture,
            JournalTextExposure text) {
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = RouteTemplateResolver.empty();
        }
        VisibleEntries projected = visibleEntries(entries, visibility, journal.lossHorizonMillis());
        InsightsSnapshot snapshot = InsightsSnapshot.of(
                        projected.entries(),
                        status,
                        resolver == null ? RouteTemplateResolver.empty() : resolver,
                        journal::records,
                        visibility::visible,
                        stack,
                        previousRun(status.runId()),
                        poolSizes,
                        journal::evictedARequestOf)
                .withExposure(text);
        List<RuntimeInsightCheckDto> checks = new ArrayList<>();
        List<RuntimeObservationDto> rows = new ArrayList<>();
        Map<String, Detail> details = new LinkedHashMap<>();
        List<Evaluated> evaluated = new ArrayList<>();
        for (Observation observation : observations) {
            List<String> hiddenPanels = projected.hiddenPanels(observation);
            String missing = missingSource(observation, snapshot, visibility, hiddenPanels);
            if (missing == null) {
                missing = observation.notApplicable(snapshot);
            }
            if (missing != null) {
                checks.add(new RuntimeInsightCheckDto(
                        observation.kind(), observation.title(), "NOT_APPLICABLE", 0, 0, missing));
                continue;
            }
            if (!capture.recorded() && readsSql(observation.reads())) {
                checks.add(new RuntimeInsightCheckDto(
                        observation.kind(), observation.title(), "UNAVAILABLE", 0, 0, capture.reason()));
                continue;
            }
            String partial = partialReason(observation, snapshot);
            List<String> unseen = unseenSources(observation, snapshot, visibility, hiddenPanels);
            if (capture.reason() != null
                    && (snapshot.records(JournalSource.SQL) || snapshot.records(JournalSource.CONNECTION))
                    && (readsSql(observation.reads()) || readsSql(observation.optionalReads(snapshot)))) {
                unseen.add(
                        capture.recorded()
                                ? capture.reason()
                                : capture.reason() + " Its SQL and connection evidence is not counted.");
            }
            Observation.Evaluation evaluation = observation.evaluate(snapshot);
            // What it could not judge is the check's, while each finding names its own route's share.
            List<String> reasons = partial != null ? new ArrayList<>(List.of(partial)) : new ArrayList<>(unseen);
            if (evaluation.uncounted() != null) {
                reasons.add(evaluation.uncounted());
            }
            String leftOut = projected.leftOut(observation, snapshot);
            if (leftOut != null) {
                // What it did not judge, so an empty result is not read as nothing found.
                reasons.add(leftOut);
            }
            if (!evaluation.hasEligibleWork()) {
                reasons.add(0, "No eligible work was recorded for this check.");
            }
            checks.add(new RuntimeInsightCheckDto(
                    observation.kind(),
                    observation.title(),
                    !evaluation.hasEligibleWork() ? "INSUFFICIENT" : partial == null ? "EVALUATED" : "PARTIAL",
                    evaluation.eligibleRequests(),
                    evaluation.findings().size(),
                    reasons.isEmpty() ? null : String.join(" ", reasons)));
            evaluated.add(new Evaluated(observation, evaluation.findings(), partial, unseen));
        }
        List<Finding> repeatedSelects = evaluated.stream()
                .filter(done -> RepeatedSelects.KIND.equals(done.observation().kind()))
                .flatMap(done -> done.findings().stream())
                .toList();
        for (Evaluated done : evaluated) {
            Observation observation = done.observation();
            String partial = done.partial();
            for (Finding found : done.findings()) {
                Finding finding = DefaultListing.apply(observation.kind(), found, repeatedSelects);
                String findingStatus =
                        !finding.sufficient() ? "INSUFFICIENT" : partial == null ? "OBSERVED" : "PARTIAL";
                List<String> limitations = new ArrayList<>(finding.limitations());
                limitations.addAll(touchedBy(snapshot.markers(), finding));
                limitations.addAll(done.unseen());
                if (partial != null) {
                    limitations.add(partial);
                }
                RuntimeObservationDto row = new RuntimeObservationDto(
                        idOf(observation.kind(), finding.key()),
                        observation.kind(),
                        finding.subject(),
                        findingStatus,
                        finding.sentence(),
                        finding.eligible(),
                        finding.affected(),
                        (finding.tier() != null ? finding.tier() : observation.minimumTier()).name(),
                        finding.whatToCheck(),
                        finding.exemplarRequestIds()
                                .subList(
                                        0,
                                        Math.min(
                                                MAX_EXEMPLARS,
                                                finding.exemplarRequestIds().size())),
                        Math.min(MAX_EVIDENCE_ROWS, finding.rows().size()),
                        limitations,
                        finding.listed(),
                        finding.unlisted());
                rows.add(row);
                details.put(row.id(), new Detail(row, finding));
            }
        }
        rows.sort(Comparator.comparing((RuntimeObservationDto row) -> "INSUFFICIENT".equals(row.status()))
                .thenComparing(Comparator.comparingLong(RuntimeObservationDto::affected)
                        .reversed())
                .thenComparing(RuntimeObservationDto::id));
        List<RuntimeInsightCoverageDto> coverage = new ArrayList<>();
        snapshot.coverage()
                .forEach((source, counts) -> coverage.add(new RuntimeInsightCoverageDto(
                        source.propertyName(),
                        counts[0] + counts[1] + counts[2] + counts[3],
                        counts[0],
                        counts[1],
                        counts[3],
                        counts[2],
                        snapshot.dropped(source))));
        List<String> limitations = new ArrayList<>();
        String markers = markersDuring(snapshot.markers());
        if (markers != null) {
            limitations.add(markers);
        }
        if (evicted > 0) {
            limitations.add("The journal evicted " + evicted + " older events, so requests before "
                    + "the oldest retained event are not projected.");
        }
        String leftOut = leftOutBeforeLoss(projected.incomplete(), projected.aiCallsLeftOut());
        if (leftOut != null) {
            limitations.add(leftOut);
        }
        String nonHttp = nonHttpExecutions(snapshot);
        if (nonHttp != null) {
            limitations.add(nonHttp);
        }
        List<String> notExercised = notExercised(snapshot, visibility, limitations);
        RuntimeInsightsReportDto report = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto(
                        status.runId(),
                        status.oldestRetainedEpochMillis(),
                        entries.isEmpty() ? null : newest(entries),
                        status.retainedEvents(),
                        snapshot.httpRequests().size(),
                        evicted,
                        status.droppedTotal()),
                coverage,
                checks,
                rows,
                limitations,
                notExercised.subList(0, Math.min(notExercised.size(), RuntimeInsightsReportDto.MAX_NOT_EXERCISED)),
                Math.max(0, notExercised.size() - RuntimeInsightsReportDto.MAX_NOT_EXERCISED));
        return new Cached(
                watermark, evicted, visibility, capture, text, report, details, status.clears(), status.dropped(), 0L);
    }

    /**
     * Scheduled runs and consumed messages the window's {@code requests} field does not count, or {@code null} when
     * the retained events hold only HTTP exchanges. Present even when those executions produced no observation.
     */
    private static String nonHttpExecutions(InsightsSnapshot snapshot) {
        long scheduled = 0;
        long messages = 0;
        for (ProjectedRequest request : snapshot.requests()) {
            if (request.kind() == ProjectedRequest.Kind.SCHEDULED) {
                scheduled++;
            } else if (request.kind() == ProjectedRequest.Kind.MESSAGE) {
                messages++;
            }
        }
        if (scheduled == 0 && messages == 0) {
            return null;
        }
        return NON_HTTP_PREFIX + " " + InsightText.counted(scheduled, "scheduled run") + " and "
                + InsightText.counted(messages, "consumed message") + ".";
    }

    /**
     * The declared routes no request of this run reached. The run's aggregates count every request, retained or
     * evicted, so an evicted request still counts as reaching its route.
     *
     * <p>Which routes were reached is HTTP Exchanges evidence ({@code docs/PLAN-v2.md} §8): while that panel is off, or
     * the journal does not record HTTP, none is listed, and the report says why rather than reading hidden or
     * unrecorded requests as absence.</p>
     */
    private List<String> notExercised(InsightsSnapshot snapshot, PanelVisibility visibility, List<String> limitations) {
        Supplier<List<MappingDto>> mappings = declaredMappings;
        if (mappings == null) {
            return List.of();
        }
        if (!snapshot.records(JournalSource.HTTP)) {
            limitations.add(ROUTE_EXERCISE_UNRECORDED);
            return List.of();
        }
        if (!visibility.visible(JournalSource.HTTP)) {
            List<String> panels = JournalSourcePanels.panelsOf(JournalSource.HTTP);
            limitations.add(
                    visibility.disabled(JournalSource.HTTP).isEmpty()
                            ? panelsLabel(panels)
                                    + " is not available in this application, so the routes no request reached"
                                    + " are not listed: " + visibility.reasonsFor(panels)
                            : panelsLabel(panels) + " is disabled, so the routes no request reached are not listed.");
            return List.of();
        }
        try {
            Set<String> exercised = new HashSet<>(snapshot.httpByRoute().keySet());
            Supplier<JournalAggregates.RouteLabels> run = runRoutes;
            JournalAggregates.RouteLabels labels = run == null ? null : run.get();
            if (labels != null) {
                exercised.addAll(labels.labels());
                if (labels.overflowed()) {
                    limitations.add("This run reached more routes than its aggregates keep, so some routes listed as"
                            + " not exercised may have been reached.");
                }
            }
            List<MappingDto> declared = mappings.get();
            if (declared == null) {
                limitations.add(ROUTE_INVENTORY_UNAVAILABLE);
                return List.of();
            }
            return NotExercisedRoutes.of(declared, exercised);
        } catch (RuntimeException ex) {
            limitations.add(ROUTE_INVENTORY_UNAVAILABLE);
            return List.of();
        }
    }

    private static Long newest(List<JournalEntry> entries) {
        long newest = Long.MIN_VALUE;
        for (JournalEntry entry : entries) {
            newest = Math.max(newest, entry.event().epochMillis());
        }
        return newest;
    }

    /**
     * The BootUI actions, availability changes, configuration refreshes, and shutdowns of the window, which explain a
     * discontinuity in what it counted (M4-7), or {@code null} when none happened.
     */
    static String markersDuring(List<RuntimeEvent> markers) {
        if (markers.isEmpty()) {
            return null;
        }
        List<String> named = new ArrayList<>();
        for (RuntimeEvent event : markers.subList(0, Math.min(MAX_NAMED_MARKERS, markers.size()))) {
            LifecyclePayload marker = (LifecyclePayload) event.payload();
            named.add(JournalActivityFeed.markerSummary(marker)
                    + (marker.target() == null ? "" : " (" + marker.target() + ")")
                    + " at " + Instant.ofEpochMilli(event.epochMillis()).truncatedTo(ChronoUnit.SECONDS));
        }
        return "During this window: " + String.join("; ", named)
                + (markers.size() > MAX_NAMED_MARKERS ? "; and " + (markers.size() - MAX_NAMED_MARKERS) + " more" : "")
                + ". What ran before and after such a change may differ.";
    }

    /** The BootUI actions that targeted a name this finding counts, such as the logger or cache it names. */
    static List<String> touchedBy(List<RuntimeEvent> markers, Finding finding) {
        List<String> touched = new ArrayList<>();
        for (RuntimeEvent event : markers) {
            LifecyclePayload marker = (LifecyclePayload) event.payload();
            String name = ControlMarkers.targetName(marker);
            if (name == null || !mentions(finding, name)) {
                continue;
            }
            touched.add("BootUI's action " + marker.target() + " at "
                    + Instant.ofEpochMilli(event.epochMillis()).truncatedTo(ChronoUnit.SECONDS)
                    + " targeted `" + name + "` during this window.");
        }
        return touched;
    }

    private static boolean mentions(Finding finding, String name) {
        if (finding.sentence().contains(name) || finding.subject().contains(name)) {
            return true;
        }
        for (List<String> row : finding.rows()) {
            for (String cell : row) {
                if (cell != null && cell.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String missingSource(
            Observation observation, InsightsSnapshot snapshot, PanelVisibility visibility, List<String> hiddenPanels) {
        for (JournalSource source : observation.reads()) {
            if (!snapshot.records(source)) {
                return "The runtime journal does not record the " + source.propertyName()
                        + " source (bootui.runtime-journal.sources).";
            }
            if (!snapshot.visible(source)) {
                List<String> panels = JournalSourcePanels.panelsOf(source);
                List<String> disabled = visibility.disabled(source);
                if (disabled.isEmpty()) {
                    // Every panel owning this source is off because the application cannot serve it, which is not the
                    // same as the user having switched it off: say so, and name what would make it available.
                    return panelsLabel(panels) + ", whose evidence this reads, " + (panels.size() == 1 ? "is" : "are")
                            + " not available in this application: " + visibility.reasonsFor(panels);
                }
                return panelsLabel(panels) + ", whose evidence this reads, " + (panels.size() == 1 ? "is" : "are")
                        + " disabled.";
            }
        }
        if (!hiddenPanels.isEmpty()
                && snapshot.requests().stream()
                        .noneMatch(request -> observation.unitKinds().contains(request.kind()))) {
            return panelsLabel(hiddenPanels) + (hiddenPanels.size() == 1 ? " is" : " are")
                    + " disabled, so its unit-of-work evidence cannot be evaluated.";
        }
        return null;
    }

    private List<String> unseenSources(
            Observation observation, InsightsSnapshot snapshot, PanelVisibility visibility, List<String> hiddenPanels) {
        List<String> unseen = new ArrayList<>();
        if (!hiddenPanels.isEmpty()) {
            unseen.add(panelsLabel(hiddenPanels) + (hiddenPanels.size() == 1 ? " is" : " are")
                    + " disabled, so its request or execution units are not counted.");
        }
        for (JournalSource source : observation.optionalReads(snapshot)) {
            if (!snapshot.records(source)) {
                unseen.add("Without the " + source.propertyName() + " source, which the runtime journal does not"
                        + " record, its evidence is not counted.");
            } else {
                // A source several panels own, like messaging, loses a broker's evidence as soon as that broker's
                // panel is disabled, even while the others keep serving theirs.
                List<String> disabled = visibility.disabled(source);
                if (!disabled.isEmpty()) {
                    unseen.add(panelsLabel(disabled) + (disabled.size() == 1 ? " is" : " are")
                            + " disabled, so its evidence is not counted.");
                }
                List<String> unavailable = visibility.unavailable(source);
                if (!unavailable.isEmpty()) {
                    unseen.add(panelsLabel(unavailable) + (unavailable.size() == 1 ? " is" : " are")
                            + " not available in this application, so its evidence is not counted: "
                            + visibility.reasonsFor(unavailable));
                }
            }
        }
        return unseen;
    }

    private RunSummary previousRun(String runId) {
        if (runs == null) {
            return null;
        }
        if (runId.equals(previousRunOf) && previousRun != null) {
            return previousRun;
        }
        RunSummary found = null;
        try {
            for (RunSummary summary : runs.get()) {
                if (!summary.header().runId().equals(runId)) {
                    found = summary;
                    break;
                }
            }
        } catch (RuntimeException ex) {
            found = null;
        }
        previousRunOf = runId;
        previousRun = found;
        return found;
    }

    /** Whether {@code sources} holds one that only a traced JDBC {@code DataSource} records. */
    private static boolean readsSql(Set<JournalSource> sources) {
        return sources.contains(JournalSource.SQL) || sources.contains(JournalSource.CONNECTION);
    }

    private static String partialReason(Observation observation, InsightsSnapshot snapshot) {
        Set<JournalSource> sources = EnumSet.noneOf(JournalSource.class);
        sources.addAll(observation.reads());
        for (JournalSource source : observation.optionalReads(snapshot)) {
            if (snapshot.available(source)) {
                sources.add(source);
            }
        }
        if (observation.unitKinds().contains(ProjectedRequest.Kind.HTTP)) {
            addAnchor(sources, JournalSource.HTTP, snapshot);
        }
        if (observation.unitKinds().contains(ProjectedRequest.Kind.SCHEDULED)) {
            addAnchor(sources, JournalSource.SCHEDULED, snapshot);
        }
        if (observation.unitKinds().contains(ProjectedRequest.Kind.MESSAGE)) {
            addAnchor(sources, JournalSource.MESSAGING, snapshot);
            addAnchor(sources, JournalSource.WEBSOCKET, snapshot);
        }
        long dropped = sources.stream().mapToLong(snapshot::dropped).sum();
        return dropped == 0
                ? null
                : "The journal dropped " + dropped + " events this observation reads, so its findings are"
                        + " incomplete: a dropped event can hide a finding, or make one appear.";
    }

    private static void addAnchor(Set<JournalSource> sources, JournalSource source, InsightsSnapshot snapshot) {
        if (snapshot.available(source)) {
            sources.add(source);
        }
    }

    /** {@code The sql-trace panel}, or {@code The kafka, rabbitmq and jms panels} when several are named. */
    private static String panelsLabel(List<String> panels) {
        if (panels.size() == 1) {
            return "The " + panels.get(0) + " panel";
        }
        return "The " + String.join(", ", panels.subList(0, panels.size() - 1)) + " and "
                + panels.get(panels.size() - 1) + " panels";
    }

    private record Detail(RuntimeObservationDto observation, Finding finding) {}

    /** One observation's findings, kept until every observation ran, since listing one may depend on another's. */
    private record Evaluated(Observation observation, List<Finding> findings, String partial, List<String> unseen) {}

    /**
     * @param incompleteByKind the requests and executions left out, by kind, because they started before an event the
     *     journal lost
     * @param aiCallsLeftOut the AI calls linked only by a trace left out for the same reason
     */
    private record VisibleEntries(
            List<JournalEntry> entries,
            Map<ProjectedRequest.Kind, List<String>> panelsByKind,
            Map<ProjectedRequest.Kind, Integer> incompleteByKind,
            int aiCallsLeftOut) {

        int incomplete() {
            return incompleteByKind.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        /** What {@code observation} would have examined but was left out, or {@code null}. */
        String leftOut(Observation observation, InsightsSnapshot snapshot) {
            int units = 0;
            for (ProjectedRequest.Kind kind : observation.unitKinds()) {
                units += incompleteByKind.getOrDefault(kind, 0);
            }
            boolean readsAi = observation.reads().contains(JournalSource.AI)
                    || observation.optionalReads(snapshot).contains(JournalSource.AI);
            return leftOutBeforeLoss(units, readsAi ? aiCallsLeftOut : 0);
        }

        List<String> hiddenPanels(Observation observation) {
            Set<String> names = new LinkedHashSet<>();
            for (ProjectedRequest.Kind kind : ProjectedRequest.Kind.values()) {
                if (observation.unitKinds().contains(kind)) {
                    names.addAll(panelsByKind.getOrDefault(kind, List.of()));
                }
            }
            return List.copyOf(names);
        }
    }

    /**
     * Every owning panel's state as one projection read it, so the cache key, the events kept, and the reason given
     * for a source left out all answer from the same read. A panel this does not name is treated as disabled.
     */
    private record PanelVisibility(Map<String, Boolean> enabled, Map<String, String> unavailable) {

        /** Whether the panel publishing {@code event} is enabled, so its evidence may be projected. */
        boolean visible(RuntimeEvent event) {
            String panel = JournalSourcePanels.panelOf(event);
            return panel == null || enabled.getOrDefault(panel, Boolean.FALSE);
        }

        /** Whether no panel owns {@code source}, or at least one that does is enabled. */
        boolean visible(JournalSource source) {
            List<String> panels = JournalSourcePanels.panelsOf(source);
            if (panels.isEmpty()) {
                return true;
            }
            for (String panel : panels) {
                if (enabled.getOrDefault(panel, Boolean.FALSE)) {
                    return true;
                }
            }
            return false;
        }

        /** The panels owning {@code source} that are off because the user disabled them. */
        List<String> disabled(JournalSource source) {
            List<String> disabled = new ArrayList<>();
            for (String panel : off(source)) {
                if (!unavailable.containsKey(panel)) {
                    disabled.add(panel);
                }
            }
            return disabled;
        }

        /** The panels owning {@code source} that are off because this application cannot serve them. */
        List<String> unavailable(JournalSource source) {
            List<String> missing = new ArrayList<>();
            for (String panel : off(source)) {
                if (unavailable.containsKey(panel)) {
                    missing.add(panel);
                }
            }
            return missing;
        }

        /** Why {@code panels} are unavailable, each reason once, in the panels' order. */
        String reasonsFor(List<String> panels) {
            List<String> reasons = new ArrayList<>();
            for (String panel : panels) {
                String reason = unavailable.get(panel);
                if (reason != null && !reasons.contains(reason)) {
                    reasons.add(reason);
                }
            }
            return String.join(" ", reasons);
        }

        private List<String> off(JournalSource source) {
            List<String> off = new ArrayList<>();
            for (String panel : JournalSourcePanels.panelsOf(source)) {
                if (!enabled.getOrDefault(panel, Boolean.FALSE)) {
                    off.add(panel);
                }
            }
            return off;
        }
    }

    private record Cached(
            long watermark,
            long evicted,
            PanelVisibility visibility,
            SqlCapture sqlCapture,
            JournalTextExposure exposure,
            RuntimeInsightsReportDto report,
            Map<String, Detail> details,
            long clears,
            Map<JournalSource, Long> dropped,
            long inventory) {

        Cached withInventory(long fingerprint) {
            return new Cached(
                    watermark,
                    evicted,
                    visibility,
                    sqlCapture,
                    exposure,
                    report,
                    details,
                    clears,
                    dropped,
                    fingerprint);
        }
    }
}
