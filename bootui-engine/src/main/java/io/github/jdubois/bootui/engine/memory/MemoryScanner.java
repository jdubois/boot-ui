package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemoryScanStatusDto;
import io.github.jdubois.bootui.core.dto.MemorySeverityCountDto;
import io.github.jdubois.bootui.core.dto.MemorySummaryDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * On-demand Memory Advisor.
 *
 * <p>The scanner aggregates the JVM data already produced by the Memory, Threads, and Heap Dump
 * panels into a {@link MemoryContext} and runs a curated registry of static health rules.
 * It never suspends threads, never changes runtime configuration, and only reads management beans.
 * The heap-content histogram requests GC and can pause the JVM; successful command execution does
 * not verify that collection ran. Other observations use the existing management beans.</p>
 *
 * <p>Framework-neutral: it reads only JMX management beans plus the shared engine
 * {@link ThreadDumpService}, so both the Spring {@code MemoryController} and the Quarkus
 * {@code MemoryResource} build it through {@link #create(ThreadDumpService, Clock)} and hold the
 * cached report through the scanner. The collector, rules, and context stay package-private; only the scanner
 * is the adapter-facing surface.</p>
 */
public final class MemoryScanner {

    static final Set<String> RETIRED_RULE_IDS = MemoryRuleRegistry.RETIRED_RULE_IDS;

    private static final String ANALYZER = "BootUI Memory Advisor";
    private static final String DISCLAIMER =
            "Heuristic JVM memory and thread health rules run against this process's live management beans only. "
                    + "These findings are review prompts, not verdicts, and should be validated against the "
                    + "application's workload and a profiler before acting.";
    private static final Comparator<MemoryRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (MemoryRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(MemoryRuleResultDto::violationCount).reversed())
            .thenComparing(MemoryRuleResultDto::id);

    private final Function<Boolean, MemoryContext> contextSupplier;
    private final boolean defaultIncludeHistogram;
    private final Clock clock;
    private final BootUiDiagnosticGcTracker diagnosticGcTracker;
    private final SingleFlightAction singleFlight = new SingleFlightAction();
    private final AdvisorScanState<MemoryReport> state = new AdvisorScanState<>(
            MemoryReport::withViolationDetails,
            () -> AdvisorScanState.ruleIds(
                    MemoryRuleRegistry.activeRules(), rule -> rule.definition().id()));

    /**
     * Post-histogram GC counters from the previous successful scan, retained across collection
     * failures for reset detection even when the recent-GC trend is interrupted. Guarded by the
     * scanner's single-flight admission.
     */
    private MemoryContext.GcSample previousGcSample;

    private boolean gcTrendInterrupted;

    /** Post-histogram latest GC event from the previous scan, guarded by single-flight admission. */
    private MemoryContext.GcEvent previousPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();

    /** Possible histogram event without a comparable epoch; never used as a time high-water mark. */
    private MemoryContext.GcEvent uncertainPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();

    /** Identity only after invalidation: its old end time must never become a new-epoch watermark. */
    private MemoryContext.GcEvent invalidatedPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();

    /** Highest GC event endTimeMillis excluded by prior scans or BootUI diagnostic GCs. */
    private long previousMaxExcludedGcEndTimeMillis = -1;

    /** Cumulative GC time and count spent in prior scans' own {@code GC.class_histogram} requests. */
    private long cumulativeHistogramGcTimeMillis;

    private long cumulativeHistogramGcCount;

    private boolean histogramGcTimeUncertain;
    private boolean histogramGcCountUncertain;

    /** Snapshot of BootUI diagnostic GC activity as of the previous scan. */
    private BootUiDiagnosticGcTracker.Snapshot previousDiagnosticSnapshot = BootUiDiagnosticGcTracker.Snapshot.empty();

    /** Successful explicit-GC observation boundary, guarded by single-flight admission. */
    private BootUiDiagnosticGcTracker.ExplicitGcCursor previousExplicitGcCursor;

    MemoryScanner(MemoryCollector collector, Clock clock) {
        this(collector::collect, clock, BootUiDiagnosticGcTracker.SHARED, true);
    }

    MemoryScanner(Supplier<MemoryContext> contextSupplier, Clock clock) {
        this(contextSupplier, clock, new BootUiDiagnosticGcTracker(() -> null, MemoryContext.GcEvent::unavailable));
    }

    MemoryScanner(Supplier<MemoryContext> contextSupplier, Clock clock, BootUiDiagnosticGcTracker diagnosticGcTracker) {
        this(ignored -> contextSupplier.get(), clock, diagnosticGcTracker, true);
    }

    MemoryScanner(
            Function<Boolean, MemoryContext> contextSupplier,
            Clock clock,
            BootUiDiagnosticGcTracker diagnosticGcTracker,
            boolean defaultIncludeHistogram) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
        this.diagnosticGcTracker = diagnosticGcTracker;
        this.defaultIncludeHistogram = defaultIncludeHistogram;
    }

    BootUiDiagnosticGcTracker diagnosticGcTracker() {
        return diagnosticGcTracker;
    }

    void recordDiagnosticGc(
            MemoryContext.GcSample before,
            MemoryContext.GcSample after,
            MemoryContext.GcEvent beforeEvent,
            MemoryContext.GcEvent afterEvent) {
        diagnosticGcTracker.recordWindow(before, after, beforeEvent, afterEvent);
    }

    /**
     * Builds the production scanner over the shared {@link ThreadDumpService} and the
     * {@code GC.class_histogram} diagnostic command, exactly as both adapters did inline before the
     * extraction. The thread snapshot retains complete summary counts and bounds per-thread detail to
     * 1,000 rows. The histogram and its GC request can be intrusive, so callers gate the scan
     * accordingly.
     */
    public static MemoryScanner create(ThreadDumpService threadDumpService, Clock clock) {
        return create(threadDumpService, clock, true);
    }

    public static MemoryScanner create(ThreadDumpService threadDumpService, Clock clock, boolean includeHistogram) {
        MemoryCollector collector = new MemoryCollector(
                () -> threadDumpService.report(null, null, 0, 1000), MemoryCollector::diagnosticCommandHistogram);
        return new MemoryScanner(collector::collect, clock, BootUiDiagnosticGcTracker.SHARED, includeHistogram);
    }

    /**
     * Reads raw presence and all values: scalar query binding can otherwise turn a present-empty
     * parameter into absence and accidentally select the configured diagnostic.
     */
    public static Boolean parseIncludeHistogram(Map<String, ? extends List<String>> queryParameters) {
        for (String name : queryParameters.keySet()) {
            if ("includeHistogram".equalsIgnoreCase(name) && !"includeHistogram".equals(name)) {
                throw new IllegalArgumentException("includeHistogram must be true or false.");
            }
        }
        if (!queryParameters.containsKey("includeHistogram")) return null;
        List<String> values = queryParameters.get("includeHistogram");
        if (values != null && values.size() == 1) {
            if ("true".equals(values.get(0))) return true;
            if ("false".equals(values.get(0))) return false;
        }
        throw new IllegalArgumentException("includeHistogram must be true or false.");
    }

    public MemoryReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Memory Advisor has not run yet. Run memory checks in the panel, or call memory_scan (bootui memory scan),"
                        + " to inspect the JVM runtime.",
                null,
                null,
                0,
                List.of(),
                AdvisorEvidenceDto.unknown(),
                defaultIncludeHistogram);
    }

    public MemoryReport scan() {
        return scan(null);
    }

    /** A null override uses the configured default; switching modes preserves this scanner's GC history. */
    public MemoryReport scan(Boolean includeHistogram) {
        boolean selected = includeHistogram == null ? defaultIncludeHistogram : includeHistogram;
        return singleFlight.run(ActionOperations.MEMORY_SCAN, () -> {
            AdvisorViolationCollector collector = state.collector();
            return state.publish(doScan(collector, selected), collector);
        });
    }

    public MemoryReport lastReport() {
        return state.currentReport(this::initialReport);
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return state.ruleViolations(ruleId, scanId, offset, limit);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        state.setRetentionLimit(limit);
    }

    private MemoryReport doScan(AdvisorViolationCollector collector, boolean includeHistogram) {
        BootUiDiagnosticGcTracker.Snapshot preDiagnostic = diagnosticGcTracker.snapshot();
        MemoryContext context;
        try {
            context = contextSupplier.apply(includeHistogram);
        } catch (RuntimeException | LinkageError ex) {
            invalidateGcTrend();
            return report(
                    "ERROR",
                    "Memory Advisor could not read the JVM runtime: " + safeMessage(ex),
                    clock.millis(),
                    null,
                    0,
                    List.of(),
                    AdvisorEvidenceDto.unknown(),
                    includeHistogram);
        }
        BootUiDiagnosticGcTracker.Snapshot postDiagnostic = diagnosticGcTracker.snapshot();

        MemoryContext.GcSample preGc = context.preHistogramGc();
        MemoryContext.GcSample postGc = context.postHistogramGc();
        boolean counterDiscontinuityDuringCollection = BootUiDiagnosticGcTracker.hasCounterDiscontinuity(preGc, postGc);
        boolean diagnosticEpochChangedDuringCollection = postDiagnostic.epoch() != preDiagnostic.epoch();
        boolean collectionEpochComparable =
                !counterDiscontinuityDuringCollection && !diagnosticEpochChangedDuringCollection;
        boolean counterDiscontinuity = counterDiscontinuityDuringCollection
                || (previousGcSample != null
                        && BootUiDiagnosticGcTracker.hasCounterDiscontinuity(previousGcSample, preGc));
        if (counterDiscontinuity) {
            boolean trackerAlreadyReset = diagnosticEpochChangedDuringCollection
                    || (!counterDiscontinuityDuringCollection
                            && preDiagnostic.epoch() != previousDiagnosticSnapshot.epoch());
            postDiagnostic = trackerAlreadyReset
                    ? diagnosticGcTracker.snapshot()
                    : diagnosticGcTracker.resetIfEpoch(postDiagnostic.epoch());
            preDiagnostic = postDiagnostic;
        }
        boolean invalidatedGcBaselines = counterDiscontinuity
                || preDiagnostic.epoch() != previousDiagnosticSnapshot.epoch()
                || postDiagnostic.epoch() != preDiagnostic.epoch();
        if (invalidatedGcBaselines) {
            cumulativeHistogramGcTimeMillis = 0L;
            cumulativeHistogramGcCount = 0L;
            histogramGcTimeUncertain = false;
            histogramGcCountUncertain = false;
            previousGcSample = null;
            if (previousPostHistogramGcEvent.available()) {
                invalidatedPostHistogramGcEvent = previousPostHistogramGcEvent;
            }
            previousPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();
            previousMaxExcludedGcEndTimeMillis = -1L;
        }

        MemoryContext.GcTrend rawTrend = previousGcSample == null || gcTrendInterrupted
                ? MemoryContext.GcTrend.unavailable()
                : MemoryContext.GcTrend.between(previousGcSample, preGc);
        MemoryContext.GcTrend trend = adjustTrendForDiagnosticGcs(rawTrend, previousDiagnosticSnapshot, preDiagnostic);
        MemoryContext.RuntimeData adjustedRuntime = collectionEpochComparable
                ? adjustRuntimeForDiagnosticGcs(
                        context.runtime(),
                        preGc,
                        cumulativeHistogramGcTimeMillis,
                        cumulativeHistogramGcCount,
                        preDiagnostic)
                : context.runtime().withGcCounters(context.runtime().uptimeMillis(), -1L, -1L);
        if (histogramGcTimeUncertain || histogramGcCountUncertain) {
            adjustedRuntime = adjustedRuntime.withGcCounters(
                    adjustedRuntime.uptimeMillis(),
                    histogramGcTimeUncertain ? -1L : adjustedRuntime.gcCollectionTimeMillis(),
                    histogramGcCountUncertain ? -1L : adjustedRuntime.gcCollectionCount());
        }
        boolean comparablePostEvent = !couldBeSameGcEvent(context.postHistogramGcEvent(), uncertainPostHistogramGcEvent)
                && !couldBeSameGcEvent(context.postHistogramGcEvent(), invalidatedPostHistogramGcEvent)
                && ((collectionEpochComparable && !invalidatedGcBaselines)
                        || isNewEpochPostHistogramEvent(preGc, postGc, context.postHistogramGcEvent()));
        MemoryContext.GcEvent latestGcEvent = !collectionEpochComparable
                        || couldBeSameGcEvent(context.latestGcEvent(), uncertainPostHistogramGcEvent)
                        || couldBeSameGcEvent(context.latestGcEvent(), invalidatedPostHistogramGcEvent)
                        || shouldSuppressGcEvent(
                                context.latestGcEvent(),
                                previousPostHistogramGcEvent,
                                previousMaxExcludedGcEndTimeMillis,
                                preDiagnostic)
                ? MemoryContext.GcEvent.unavailable()
                : context.latestGcEvent();

        previousGcSample = postGc;
        gcTrendInterrupted = false;
        if (context.postHistogramGcEvent().available()) {
            if (comparablePostEvent) {
                previousPostHistogramGcEvent = context.postHistogramGcEvent();
            } else {
                uncertainPostHistogramGcEvent = context.postHistogramGcEvent();
            }
        }
        previousMaxExcludedGcEndTimeMillis = Math.max(
                Math.max(
                        previousMaxExcludedGcEndTimeMillis,
                        comparablePostEvent ? context.postHistogramGcEvent().endTimeMillis() : -1L),
                postDiagnostic.maxEndTimeMillis());
        previousDiagnosticSnapshot = postDiagnostic;
        long concurrentDiagTime = postDiagnostic.epoch() == preDiagnostic.epoch()
                ? Math.max(0L, postDiagnostic.gcTimeMillis() - preDiagnostic.gcTimeMillis())
                : 0L;
        long concurrentDiagCount = postDiagnostic.epoch() == preDiagnostic.epoch()
                ? Math.max(0L, postDiagnostic.gcCount() - preDiagnostic.gcCount())
                : 0L;
        if (collectionEpochComparable && preGc.gcTimeMillis() >= 0 && postGc.gcTimeMillis() >= preGc.gcTimeMillis()) {
            long histogramOnlyTime = Math.max(0L, (postGc.gcTimeMillis() - preGc.gcTimeMillis()) - concurrentDiagTime);
            cumulativeHistogramGcTimeMillis =
                    BootUiDiagnosticGcTracker.saturateAdd(cumulativeHistogramGcTimeMillis, histogramOnlyTime);
        }
        if (collectionEpochComparable && preGc.gcCount() >= 0 && postGc.gcCount() >= preGc.gcCount()) {
            long histogramOnlyCount = Math.max(0L, (postGc.gcCount() - preGc.gcCount()) - concurrentDiagCount);
            cumulativeHistogramGcCount =
                    BootUiDiagnosticGcTracker.saturateAdd(cumulativeHistogramGcCount, histogramOnlyCount);
        }
        if (includeHistogram && !collectionEpochComparable) {
            recordResetHistogramGc(context, postDiagnostic, comparablePostEvent);
        }

        MemoryContext withEvent =
                context.withRuntime(adjustedRuntime).withGcTrend(trend).withLatestGcEvent(latestGcEvent);
        long explicitGcBoundary = preGc.uptimeMillis() >= 0 ? postGc.uptimeMillis() : -1L;
        BootUiDiagnosticGcTracker.ExplicitGcObservation explicitObservation = diagnosticGcTracker.observeExplicitGc(
                previousExplicitGcCursor,
                postDiagnostic.epoch(),
                explicitGcBoundary,
                counterDiscontinuity || diagnosticEpochChangedDuringCollection);
        MemoryContext withExplicitGc = withEvent.withExplicitGc(explicitObservation.data());
        MemoryContext evaluated = withExplicitGc.withViolationCollector(collector);

        List<MemoryEvaluation> evaluations = MemoryRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluateWithEvidence(evaluated))
                .toList();
        List<MemoryRuleResultDto> results =
                evaluations.stream().map(MemoryEvaluation::result).toList();
        boolean hadErrors = results.stream().anyMatch(result -> MemoryRuleSupport.ERROR.equals(result.status()));
        String status = hadErrors ? "PARTIAL" : "SCANNED";
        String message = "Memory Advisor evaluated " + results.size() + " rules against the JVM runtime.";
        message += includeHistogram
                ? " Heap histogram requested (may request full GC and pause the JVM)."
                : " Heap histogram omitted; histogram-dependent checks were skipped.";
        if (hadErrors) {
            message += " Some rules could not be evaluated.";
        }
        MemoryReport completed = report(
                status,
                message,
                clock.millis(),
                summary(evaluated),
                results.size(),
                results,
                evidence(evaluations, includeHistogram),
                includeHistogram);
        previousExplicitGcCursor = explicitObservation.cursor();
        return completed;
    }

    private static MemoryContext.GcTrend adjustTrendForDiagnosticGcs(
            MemoryContext.GcTrend rawTrend,
            BootUiDiagnosticGcTracker.Snapshot previousDiagnostic,
            BootUiDiagnosticGcTracker.Snapshot currentDiagnostic) {
        if (!rawTrend.available() || currentDiagnostic.epoch() != previousDiagnostic.epoch()) {
            return rawTrend;
        }
        long diagTimeDelta = Math.max(0L, currentDiagnostic.gcTimeMillis() - previousDiagnostic.gcTimeMillis());
        long diagCountDelta = Math.max(0L, currentDiagnostic.gcCount() - previousDiagnostic.gcCount());
        boolean hasCollectorDiag =
                !currentDiagnostic.perCollectorCounts().equals(previousDiagnostic.perCollectorCounts());
        if (diagTimeDelta == 0L && diagCountDelta == 0L && !hasCollectorDiag) {
            return rawTrend;
        }
        long adjustedTime =
                rawTrend.deltaGcTimeMillis() < 0 ? -1L : Math.max(0L, rawTrend.deltaGcTimeMillis() - diagTimeDelta);
        long adjustedCount = rawTrend.deltaGcCount() < 0 ? -1L : Math.max(0L, rawTrend.deltaGcCount() - diagCountDelta);
        Map<String, Long> adjustedCollectors = new LinkedHashMap<>(rawTrend.perCollectorDeltas());
        for (Map.Entry<String, Long> entry : rawTrend.perCollectorDeltas().entrySet()) {
            long prevDiag = previousDiagnostic.perCollectorCounts().getOrDefault(entry.getKey(), 0L);
            long currDiag = currentDiagnostic.perCollectorCounts().getOrDefault(entry.getKey(), 0L);
            long diagDelta = Math.max(0L, currDiag - prevDiag);
            adjustedCollectors.put(entry.getKey(), Math.max(0L, entry.getValue() - diagDelta));
        }
        return new MemoryContext.GcTrend(
                true, adjustedTime, rawTrend.deltaUptimeMillis(), adjustedCount, adjustedCollectors);
    }

    private static MemoryContext.RuntimeData adjustRuntimeForDiagnosticGcs(
            MemoryContext.RuntimeData runtime,
            MemoryContext.GcSample preGc,
            long cumulativeHistogramGcTimeMillis,
            long cumulativeHistogramGcCount,
            BootUiDiagnosticGcTracker.Snapshot currentDiagnostic) {
        long baseUptime = preGc.uptimeMillis() > 0 ? preGc.uptimeMillis() : runtime.uptimeMillis();
        long baseGcTime = preGc.gcTimeMillis() >= 0 ? preGc.gcTimeMillis() : runtime.gcCollectionTimeMillis();
        long baseGcCount = preGc.gcCount() >= 0 ? preGc.gcCount() : runtime.gcCollectionCount();
        long totalDiagTime = BootUiDiagnosticGcTracker.saturateAdd(
                cumulativeHistogramGcTimeMillis, currentDiagnostic.gcTimeMillis());
        long totalDiagCount =
                BootUiDiagnosticGcTracker.saturateAdd(cumulativeHistogramGcCount, currentDiagnostic.gcCount());
        long adjustedGcTime = baseGcTime < 0 ? -1L : Math.max(0L, baseGcTime - totalDiagTime);
        long adjustedGcCount = baseGcCount < 0 ? -1L : Math.max(0L, baseGcCount - totalDiagCount);
        if (baseUptime == runtime.uptimeMillis()
                && adjustedGcTime == runtime.gcCollectionTimeMillis()
                && adjustedGcCount == runtime.gcCollectionCount()) {
            return runtime;
        }
        return runtime.withGcCounters(baseUptime, adjustedGcTime, adjustedGcCount);
    }

    private void recordResetHistogramGc(
            MemoryContext context, BootUiDiagnosticGcTracker.Snapshot diagnostic, boolean comparablePostEvent) {
        MemoryContext.GcSample post = context.postHistogramGc();
        MemoryContext.GcEvent event = context.postHistogramGcEvent();
        boolean singleOwnCollection = comparablePostEvent && event.id() == 1L && post.gcCount() == 1L;
        for (Map.Entry<String, Long> collector : post.perCollectorCounts().entrySet()) {
            if (!MemoryCollector.isConcurrentCycleBean(collector.getKey())
                    && (collector.getValue() < 0
                            || (!collector.getKey().equals(event.collectorName()) && collector.getValue() > 0))) {
                singleOwnCollection = false;
            }
        }
        if (!singleOwnCollection) {
            histogramGcTimeUncertain = true;
            histogramGcCountUncertain = true;
            return;
        }
        MemoryContext.GcSample pre = context.preHistogramGc();
        boolean timerRebased =
                pre.gcTimeMillis() >= 0 && post.gcTimeMillis() >= 0 && post.gcTimeMillis() < pre.gcTimeMillis();
        boolean wholeTimerOwned = timerRebased
                && hasSoleTimingCollector(pre, event.collectorName())
                && hasSoleTimingCollector(post, event.collectorName());
        // A net decrease can hide retained time from another collector, even at zero count.
        // Count proof stays separate; GcInfo duration cannot establish whole-timer ownership.
        BootUiDiagnosticGcTracker.DiagnosticAccounting accounted = diagnosticGcTracker.diagnosticAccounting(
                diagnostic, event, wholeTimerOwned ? post.gcTimeMillis() : -1L);
        if (accounted.time() == BootUiDiagnosticGcTracker.Accounting.UNKNOWN) {
            histogramGcTimeUncertain = true;
        } else if (accounted.time() == BootUiDiagnosticGcTracker.Accounting.NOT_INCLUDED) {
            cumulativeHistogramGcTimeMillis =
                    BootUiDiagnosticGcTracker.saturateAdd(cumulativeHistogramGcTimeMillis, post.gcTimeMillis());
        }
        if (accounted.count() == BootUiDiagnosticGcTracker.Accounting.UNKNOWN) {
            histogramGcCountUncertain = true;
        } else if (accounted.count() == BootUiDiagnosticGcTracker.Accounting.NOT_INCLUDED) {
            cumulativeHistogramGcCount = BootUiDiagnosticGcTracker.saturateAdd(cumulativeHistogramGcCount, 1L);
        }
    }

    private static boolean hasSoleTimingCollector(MemoryContext.GcSample sample, String collectorName) {
        boolean found = false;
        for (String name : sample.perCollectorCounts().keySet()) {
            if (MemoryCollector.isConcurrentCycleBean(name)) {
                continue;
            }
            if (found || !name.equals(collectorName)) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean isNewEpochPostHistogramEvent(
            MemoryContext.GcSample before, MemoryContext.GcSample after, MemoryContext.GcEvent event) {
        if (!event.available()
                || event.id() < 0
                || event.collectorName() == null
                || before.uptimeMillis() < 0
                || after.uptimeMillis() < before.uptimeMillis()
                || event.endTimeMillis() <= before.uptimeMillis()
                || event.endTimeMillis() > after.uptimeMillis()) {
            return false;
        }
        // A completion inside a monotonic sample interval plus the current collector-local id
        // ties this event to the post-reset counters, not to an old clock or cached notification.
        Long beforeCount = before.perCollectorCounts().get(event.collectorName());
        Long afterCount = after.perCollectorCounts().get(event.collectorName());
        return beforeCount != null && beforeCount >= 0 && afterCount != null && afterCount == event.id();
    }

    private static boolean couldBeSameGcEvent(MemoryContext.GcEvent candidate, MemoryContext.GcEvent uncertain) {
        if (!candidate.available() || !uncertain.available()) {
            return false;
        }
        if (candidate.collectorName() != null
                && uncertain.collectorName() != null
                && !candidate.collectorName().equals(uncertain.collectorName())) {
            return false;
        }
        if (candidate.id() >= 0 && uncertain.id() >= 0 && candidate.id() != uncertain.id()) {
            return false;
        }
        return candidate.endTimeMillis() < 0
                || uncertain.endTimeMillis() < 0
                || candidate.endTimeMillis() == uncertain.endTimeMillis();
    }

    private static boolean shouldSuppressGcEvent(
            MemoryContext.GcEvent candidate,
            MemoryContext.GcEvent previousHistogramEvent,
            long previousMaxEndTimeMillis,
            BootUiDiagnosticGcTracker.Snapshot diagnostic) {
        if (candidate == null || !candidate.available()) {
            return true;
        }
        if (candidate.sameEvent(previousHistogramEvent) || candidate.sameEvent(diagnostic.latestEvent())) {
            return true;
        }
        if (candidate.id() < 0 && candidate.endTimeMillis() < 0) {
            return diagnostic.latestEvent().available()
                    && diagnostic.latestEvent().id() < 0
                    && diagnostic.latestEvent().endTimeMillis() < 0
                    && candidate.durationMillis() == diagnostic.latestEvent().durationMillis()
                    && java.util.Objects.equals(
                            candidate.collectorName(), diagnostic.latestEvent().collectorName());
        }
        long highWaterMark = Math.max(previousMaxEndTimeMillis, diagnostic.maxEndTimeMillis());
        if (highWaterMark >= 0 && candidate.endTimeMillis() >= 0) {
            if (candidate.endTimeMillis() < highWaterMark) {
                return true;
            }
            if (candidate.endTimeMillis() == highWaterMark) {
                return !isStrictlyNewerSameMillisecondEvent(
                        candidate, previousHistogramEvent, diagnostic.latestEvent(), highWaterMark);
            }
        }
        return false;
    }

    private static boolean isStrictlyNewerSameMillisecondEvent(
            MemoryContext.GcEvent candidate,
            MemoryContext.GcEvent previousHistogramEvent,
            MemoryContext.GcEvent diagnosticEvent,
            long highWaterMark) {
        if (candidate.id() < 0) {
            return false;
        }
        boolean compared = false;
        if (previousHistogramEvent != null
                && previousHistogramEvent.available()
                && previousHistogramEvent.endTimeMillis() == highWaterMark
                && java.util.Objects.equals(candidate.collectorName(), previousHistogramEvent.collectorName())) {
            compared = true;
            if (previousHistogramEvent.id() < 0 || candidate.id() <= previousHistogramEvent.id()) {
                return false;
            }
        }
        if (diagnosticEvent != null
                && diagnosticEvent.available()
                && diagnosticEvent.endTimeMillis() == highWaterMark
                && java.util.Objects.equals(candidate.collectorName(), diagnosticEvent.collectorName())) {
            compared = true;
            if (diagnosticEvent.id() < 0 || candidate.id() <= diagnosticEvent.id()) {
                return false;
            }
        }
        return compared;
    }

    private void invalidateGcTrend() {
        gcTrendInterrupted = true;
    }

    private MemoryReport report(
            String status,
            String message,
            Long scannedAt,
            MemorySummaryDto summary,
            int rulesEvaluated,
            List<MemoryRuleResultDto> results,
            AdvisorEvidenceDto evidence,
            boolean includeHistogram) {
        List<MemoryRuleResultDto> violations = results.stream()
                .filter(MemoryScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
        int violationsFound = violations.size();
        MemoryScanStatusDto scan = new MemoryScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, violationsFound, includeHistogram);
        return new MemoryReport(
                true,
                DISCLAIMER,
                rulesEvaluated,
                violationsFound,
                summary,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results),
                evidence);
    }

    public MemoryReport applyDismissals(MemoryReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<MemoryRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<MemoryRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        MemoryScanStatusDto scan = report.scan();
        MemoryScanStatusDto updatedScan = new MemoryScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                violationsFound,
                scan.includeHistogram());
        return new MemoryReport(
                report.localOnly(),
                report.disclaimer(),
                report.rulesEvaluated(),
                violationsFound,
                report.summary(),
                severityCounts(active),
                updatedScan,
                marked,
                report.analysisErrors(),
                report.evidence(),
                report.violationDetails());
    }

    static AdvisorEvidenceDto evidence(List<MemoryEvaluation> evaluations) {
        return evidence(evaluations, true);
    }

    private static AdvisorEvidenceDto evidence(List<MemoryEvaluation> evaluations, boolean includeHistogram) {
        if (evaluations.isEmpty()) return AdvisorEvidenceDto.unknown();
        List<String> limitations = evaluations.stream()
                .filter(MemoryEvaluation::requiredUnknown)
                .map(evaluation -> evaluation.result().id()
                        + (!includeHistogram && evaluation.result().id().startsWith("MEM-CONTENT-")
                                ? ": heap histogram omitted by scan selection; check was not assessed."
                                : ": required JVM evidence was unavailable or evaluation failed."))
                .limit(20)
                .toList();
        return new AdvisorEvidenceDto(
                evaluations.stream().anyMatch(MemoryEvaluation::usable), limitations.isEmpty(), limitations);
    }

    static List<MemoryRuleResultDto> analysisErrors(List<MemoryRuleResultDto> results) {
        return results.stream()
                .filter(result -> MemoryRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(MemoryRuleResultDto::id))
                .toList();
    }

    private MemorySummaryDto summary(MemoryContext context) {
        return new MemorySummaryDto(
                context.memory().heapUsed(),
                context.memory().heapMax(),
                context.heapUsedPercent(),
                context.threads().total(),
                context.threads().peak(),
                context.threads().deadlockDetected(),
                context.classLoading().loadedClasses(),
                context.heapContent().available());
    }

    private List<MemorySeverityCountDto> severityCounts(List<MemoryRuleResultDto> violations) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                violations, MemoryRuleResultDto::severity, MemoryRuleResultDto::violationCount);
        return counts.entrySet().stream()
                .map(entry -> new MemorySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean isViolation(MemoryRuleResultDto result) {
        return MemoryRuleSupport.VIOLATION.equals(result.status());
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }
}
