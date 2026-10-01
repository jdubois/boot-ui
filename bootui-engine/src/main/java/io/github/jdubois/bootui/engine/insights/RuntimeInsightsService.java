package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsWindowDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime Insights ({@code docs/PLAN-v2.md} §5.4, §5.5), shared by every adapter: projects the journal's retained events
 * on read, evaluates every observation, and caches the result until the journal records more.
 *
 * <p>An observation whose source the journal does not record, or whose panel is disabled, is reported not applicable
 * with the reason, never silently skipped. One whose source dropped events reports its findings as {@code PARTIAL}.</p>
 */
public final class RuntimeInsightsService {

    /** The evidence rows an observation's detail returns at most. */
    public static final int MAX_EVIDENCE_ROWS = 20;

    /** The exemplar request ids an observation names at most. */
    public static final int MAX_EXEMPLARS = 3;

    static final String DISABLED = "Runtime Insights reads the runtime journal, which is disabled"
            + " (bootui.runtime-journal.enabled=false).";

    private final RuntimeJournal journal;
    private final Supplier<RouteTemplateResolver> routes;
    private final Predicate<String> panelEnabled;
    private final List<Observation> observations;
    private final InsightsStack stack;
    private final Supplier<List<RunSummary>> runs;
    private Cached cached;
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
        this(journal, routes, panelEnabled, stack, runs, defaultObservations());
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

    /** The observations of 2.0, in report order. */
    public static List<Observation> defaultObservations() {
        return List.of(
                new ExceptionHotspots(),
                new ErrorsBehind2xx(),
                new RepeatedSelects(),
                new ConnectionsPerRequest(),
                new SafeMethodDml(),
                new SplitTransactionWrites(),
                new LazySqlAfterHandler(),
                new EventLoopBlocking(),
                new FrameworkWarningsByRoute());
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
                    new RuntimeInsightsReportDto(false, DISABLED, null, List.of(), List.of(), List.of(), List.of()),
                    Map.of());
        }
        JournalStatus status = journal.status();
        long watermark = status.lastSequence();
        long evicted = status.evictedByCount() + status.evictedByBytes();
        if (cached != null && cached.watermark() == watermark && cached.evicted() == evicted) {
            return cached;
        }
        cached = project(status, journal.entries(), watermark, evicted);
        return cached;
    }

    private Cached project(JournalStatus status, List<JournalEntry> entries, long watermark, long evicted) {
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = RouteTemplateResolver.empty();
        }
        InsightsSnapshot snapshot = InsightsSnapshot.of(
                entries,
                status,
                resolver == null ? RouteTemplateResolver.empty() : resolver,
                journal::records,
                this::panelVisible,
                stack,
                previousRun(status.runId()));
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
            String partial = partialReason(observation, snapshot);
            List<String> unseen = unseenSources(observation, snapshot);
            Observation.Evaluation evaluation = observation.evaluate(snapshot);
            checks.add(new RuntimeInsightCheckDto(
                    observation.kind(),
                    observation.title(),
                    partial == null ? "EVALUATED" : "PARTIAL",
                    evaluation.eligibleRequests(),
                    evaluation.findings().size(),
                    partial != null ? partial : unseen.isEmpty() ? null : String.join(" ", unseen)));
            for (Finding finding : evaluation.findings()) {
                String findingStatus =
                        !finding.sufficient() ? "INSUFFICIENT" : partial == null ? "OBSERVED" : "PARTIAL";
                List<String> limitations = new ArrayList<>(finding.limitations());
                limitations.addAll(unseen);
                if (partial != null) {
                    limitations.add(partial);
                }
                RuntimeObservationDto row = new RuntimeObservationDto(
                        observation.kind() + ":" + finding.key(),
                        observation.kind(),
                        finding.subject(),
                        findingStatus,
                        finding.sentence(),
                        finding.eligible(),
                        finding.affected(),
                        observation.minimumTier().name(),
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
                        counts[0] + counts[1] + counts[2],
                        counts[0],
                        counts[1],
                        counts[2],
                        snapshot.dropped(source))));
        List<String> limitations = new ArrayList<>();
        if (evicted > 0) {
            limitations.add("The journal evicted " + evicted + " older events, so requests before "
                    + "the oldest retained event are not projected.");
        }
        RuntimeInsightsReportDto report = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto(
                        status.runId(),
                        status.oldestRetainedEpochMillis(),
                        entries.isEmpty() ? null : newest(entries),
                        status.retainedEvents(),
                        snapshot.requests().size(),
                        evicted,
                        status.droppedTotal()),
                coverage,
                checks,
                rows,
                limitations);
        return new Cached(watermark, evicted, report, details);
    }

    private static Long newest(List<JournalEntry> entries) {
        long newest = Long.MIN_VALUE;
        for (JournalEntry entry : entries) {
            newest = Math.max(newest, entry.event().epochMillis());
        }
        return newest;
    }

    private String missingSource(Observation observation, InsightsSnapshot snapshot) {
        for (JournalSource source : observation.reads()) {
            if (!snapshot.records(source)) {
                return "The runtime journal does not record the " + source.propertyName()
                        + " source (bootui.runtime-journal.sources).";
            }
            if (!snapshot.visible(source)) {
                return "The " + panelOf(source) + " panel, whose evidence this reads, is disabled.";
            }
        }
        return null;
    }

    private List<String> unseenSources(Observation observation, InsightsSnapshot snapshot) {
        List<String> unseen = new ArrayList<>();
        for (JournalSource source : observation.optionalReads()) {
            if (!snapshot.records(source)) {
                unseen.add("Without the " + source.propertyName() + " source, which the runtime journal does not"
                        + " record, its evidence is not counted.");
            } else if (!snapshot.visible(source)) {
                unseen.add("The " + panelOf(source) + " panel is disabled, so its evidence is not counted.");
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

    private boolean panelVisible(JournalSource source) {
        String panel = panelOf(source);
        try {
            return panel == null || panelEnabled.test(panel);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String panelOf(JournalSource source) {
        return switch (source) {
            case SQL, CONNECTION -> BootUiPanels.SQL_TRACE;
            case HTTP -> BootUiPanels.HTTP_EXCHANGES;
            case EXCEPTION -> BootUiPanels.EXCEPTIONS;
            case TRANSACTION -> BootUiPanels.TRANSACTIONS;
            case LOG -> BootUiPanels.LOG_TAIL;
            default -> null;
        };
    }

    private record Detail(RuntimeObservationDto observation, Finding finding) {}

    private record Cached(long watermark, long evicted, RuntimeInsightsReportDto report, Map<String, Detail> details) {}
}
