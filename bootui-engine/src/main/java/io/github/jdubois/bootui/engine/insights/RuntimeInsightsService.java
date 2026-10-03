package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsWindowDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
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
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    /** The evidence rows an observation's detail returns at most. */
    public static final int MAX_EVIDENCE_ROWS = 20;

    /** The exemplar request ids an observation names at most. */
    public static final int MAX_EXEMPLARS = 3;

    /** The markers a report's limitation names at most. */
    static final int MAX_NAMED_MARKERS = 3;

    /** Why Runtime Insights is unavailable without the journal, naming the property to set. */
    public static final String DISABLED = "Runtime Insights reads the runtime journal, which is disabled:"
            + " set bootui.runtime-journal.enabled=true.";

    /** Why no route is listed as not exercised when the application's declared routes could not be read. */
    public static final String ROUTE_INVENTORY_UNAVAILABLE = "The application's declared routes could not be read, so"
            + " the routes no request reached are not listed: that does not mean every route was exercised.";

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
     * Installs the maximum size of each connection pool, by the data source name its connections carry, which
     * {@code transaction-across-remote-call} uses for its labelled estimate.
     */
    public synchronized void setPoolSizes(Function<String, Integer> poolSizes) {
        this.poolSizes = poolSizes;
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
                new WorkAfterResponse());
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

    private Cached current() {
        if (journal == null || !journal.settings().enabled()) {
            return new Cached(
                    -1,
                    -1,
                    0,
                    SqlCapture.capturing(),
                    null,
                    new RuntimeInsightsReportDto(
                            false, DISABLED, null, List.of(), List.of(), List.of(), List.of(), List.of(), 0),
                    Map.of());
        }
        JournalStatus status = journal.status();
        long watermark = status.lastSequence();
        long evicted = status.evictedByCount() + status.evictedByBytes();
        // A panel disabled or re-enabled since the last read changes what may be shown, so it invalidates the cache.
        PanelVisibility visibility = panelVisibility();
        SqlCapture capture = sqlCapture();
        // So is a live change of the exposure policy, which changes what recorded text may be quoted (§8).
        JournalTextExposure text = JournalTextExposure.of(exposure);
        if (cached != null
                && cached.watermark() == watermark
                && cached.evicted() == evicted
                && cached.visibility() == visibility.mask()
                && cached.sqlCapture().equals(capture)
                && text.equals(cached.exposure())) {
            return cached;
        }
        cached = project(status, journal.entries(), watermark, evicted, visibility, capture, text);
        return cached;
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
        long mask = 0;
        int bit = 0;
        for (String panel : JournalSourcePanels.owningPanels()) {
            boolean on;
            try {
                on = panelEnabled.test(panel);
            } catch (RuntimeException ex) {
                on = false;
            }
            enabled.put(panel, on);
            if (on) {
                mask |= 1L << bit;
            }
            bit++;
        }
        return new PanelVisibility(Map.copyOf(enabled), mask);
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
    private List<JournalEntry> visibleEntries(List<JournalEntry> entries, PanelVisibility visibility) {
        Set<String> hidden = new HashSet<>();
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (anchorsAUnitOfWork(event) && !visibility.visible(event)) {
                hidden.add(unitOf(event));
            }
        }
        List<JournalEntry> visible = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (!visibility.visible(event)) {
                continue;
            }
            String unit = unitOf(event);
            if (unit != null && hidden.contains(unit)) {
                continue;
            }
            visible.add(entry);
        }
        return visible;
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
        InsightsSnapshot snapshot = InsightsSnapshot.of(
                        visibleEntries(entries, visibility),
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
        for (Observation observation : observations) {
            String missing = missingSource(observation, snapshot);
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
            List<String> unseen = unseenSources(observation, snapshot, visibility);
            if (capture.reason() != null
                    && (snapshot.records(JournalSource.SQL) || snapshot.records(JournalSource.CONNECTION))
                    && (readsSql(observation.reads()) || readsSql(observation.optionalReads()))) {
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
            for (Finding finding : evaluation.findings()) {
                String findingStatus =
                        !finding.sufficient() ? "INSUFFICIENT" : partial == null ? "OBSERVED" : "PARTIAL";
                List<String> limitations = new ArrayList<>(finding.limitations());
                limitations.addAll(touchedBy(snapshot.markers(), finding));
                limitations.addAll(unseen);
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
                        limitations);
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
        List<String> notExercised = notExercised(snapshot, limitations);
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
        return new Cached(watermark, evicted, visibility.mask(), capture, text, report, details);
    }

    /**
     * The declared routes no request of this run reached. The run's aggregates count every request, retained or
     * evicted, so an evicted request still counts as reaching its route.
     */
    private List<String> notExercised(InsightsSnapshot snapshot, List<String> limitations) {
        Supplier<List<MappingDto>> mappings = declaredMappings;
        if (mappings == null) {
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

    private String missingSource(Observation observation, InsightsSnapshot snapshot) {
        for (JournalSource source : observation.reads()) {
            if (!snapshot.records(source)) {
                return "The runtime journal does not record the " + source.propertyName()
                        + " source (bootui.runtime-journal.sources).";
            }
            if (!snapshot.visible(source)) {
                List<String> panels = JournalSourcePanels.panelsOf(source);
                return panelsLabel(panels) + ", whose evidence this reads, " + (panels.size() == 1 ? "is" : "are")
                        + " disabled.";
            }
        }
        return null;
    }

    private List<String> unseenSources(Observation observation, InsightsSnapshot snapshot, PanelVisibility visibility) {
        List<String> unseen = new ArrayList<>();
        for (JournalSource source : observation.optionalReads()) {
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
        long dropped = 0;
        for (JournalSource source : observation.reads()) {
            dropped += snapshot.dropped(source);
        }
        for (JournalSource source : observation.optionalReads()) {
            if (snapshot.records(source)) {
                dropped += snapshot.dropped(source);
            }
        }
        dropped += snapshot.dropped(JournalSource.HTTP);
        return dropped == 0
                ? null
                : "The journal dropped " + dropped + " events this observation reads, so its counts are a floor.";
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

    /**
     * Every owning panel's state as one projection read it, so the cache key, the events kept, and the reason given
     * for a source left out all answer from the same read. A panel this does not name is treated as disabled.
     */
    private record PanelVisibility(Map<String, Boolean> enabled, long mask) {

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

        /** The panels owning {@code source} that are disabled, so their evidence is left out of this projection. */
        List<String> disabled(JournalSource source) {
            List<String> disabled = new ArrayList<>();
            for (String panel : JournalSourcePanels.panelsOf(source)) {
                if (!enabled.getOrDefault(panel, Boolean.FALSE)) {
                    disabled.add(panel);
                }
            }
            return disabled;
        }
    }

    private record Cached(
            long watermark,
            long evicted,
            long visibility,
            SqlCapture sqlCapture,
            JournalTextExposure exposure,
            RuntimeInsightsReportDto report,
            Map<String, Detail> details) {}
}
