package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemoryScanStatusDto;
import io.github.jdubois.bootui.core.dto.MemorySeverityCountDto;
import io.github.jdubois.bootui.core.dto.MemorySummaryDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * cached report themselves. The collector, rules, and context stay package-private; only the scanner
 * is the adapter-facing surface.</p>
 */
public final class MemoryScanner {

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

    private final Supplier<MemoryContext> contextSupplier;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    /**
     * Post-histogram GC counters from the previous scan, used as the lower bound of the recent-GC
     * window. Guarded by the scanner's single-flight admission.
     */
    private MemoryContext.GcSample previousGcSample;

    /** Post-histogram latest GC event from the previous scan, guarded by single-flight admission. */
    private MemoryContext.GcEvent previousPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();

    /**
     * Cross-scan state for MEM-POOL-007's buffer-pool growth-without-release trend: each pool's used
     * bytes and consecutive-increase streak as of the previous scan. Guarded by single-flight admission.
     */
    private Map<String, Long> previousBufferPoolUsed = Map.of();

    private Map<String, Integer> previousBufferPoolStreaks = Map.of();
    private boolean previousBufferPoolSampleAvailable;

    /**
     * Cross-scan state for MEM-HEAP-008's post-histogram old-generation usage trend. Guarded by
     * single-flight admission.
     */
    private long previousOldGenUsedBytes = -1;

    private int previousOldGenStreak;
    private boolean previousOldGenSampleAvailable;

    MemoryScanner(MemoryCollector collector, Clock clock) {
        this(collector::collect, clock);
    }

    MemoryScanner(Supplier<MemoryContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    /**
     * Builds the production scanner over the shared {@link ThreadDumpService} and the
     * {@code GC.class_histogram} diagnostic command, exactly as both adapters did inline before the
     * extraction. The thread snapshot retains complete summary counts but bounds per-thread detail to
     * 1,000 rows; the CPU-hot-thread rule skips when that detail is truncated. The histogram and its
     * GC request can be intrusive, so callers gate the scan accordingly.
     */
    public static MemoryScanner create(ThreadDumpService threadDumpService, Clock clock) {
        return new MemoryScanner(
                new MemoryCollector(
                        () -> threadDumpService.report(null, null, 0, 1000),
                        MemoryCollector::diagnosticCommandHistogram),
                clock);
    }

    public MemoryReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Memory Advisor has not run yet. Click Run memory checks to inspect the JVM runtime.",
                null,
                null,
                0,
                List.of());
    }

    public MemoryReport scan() {
        return singleFlight.run(ActionOperations.MEMORY_SCAN, this::doScan);
    }

    private MemoryReport doScan() {
        MemoryContext context;
        try {
            context = contextSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            resetObservations();
            return report(
                    "ERROR",
                    "Memory Advisor could not read the JVM runtime: " + safeMessage(ex),
                    clock.millis(),
                    null,
                    0,
                    List.of());
        }

        MemoryContext.GcSample currentSample = context.postHistogramGc();
        MemoryContext.GcTrend trend = previousGcSample == null
                ? MemoryContext.GcTrend.unavailable()
                : MemoryContext.GcTrend.between(previousGcSample, context.preHistogramGc());
        previousGcSample = currentSample;
        MemoryContext.GcEvent latestGcEvent = context.latestGcEvent().sameEvent(previousPostHistogramGcEvent)
                ? MemoryContext.GcEvent.unavailable()
                : context.latestGcEvent();
        previousPostHistogramGcEvent = context.postHistogramGcEvent();
        MemoryContext.BufferPoolTrend bufferPoolTrend =
                computeBufferPoolTrend(context.memory().bufferPools());
        MemoryContext.OldGenTrend oldGenTrend = computeOldGenTrend(context.postGcHeap());
        MemoryContext evaluated = context.withGcTrend(trend)
                .withLatestGcEvent(latestGcEvent)
                .withBufferPoolTrend(bufferPoolTrend)
                .withOldGenTrend(oldGenTrend);

        List<MemoryEvaluation> evaluations = MemoryRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluateWithEvidence(evaluated))
                .toList();
        List<MemoryRuleResultDto> results =
                evaluations.stream().map(MemoryEvaluation::result).toList();
        boolean hadErrors = results.stream().anyMatch(result -> MemoryRuleSupport.ERROR.equals(result.status()));
        String status = hadErrors ? "PARTIAL" : "SCANNED";
        String message = "Memory Advisor evaluated " + results.size() + " rules against the JVM runtime.";
        if (hadErrors) {
            message += " Some rules could not be evaluated.";
        }
        return report(
                status, message, clock.millis(), summary(evaluated), results.size(), results, evidence(evaluations));
    }

    /**
     * Computes each buffer pool's consecutive-increase streak against the previous scan's readings
     * (MEM-POOL-007's net-growth signal). A pool's streak grows only when its used-byte
     * reading strictly increased since the previous scan; a decrease, a plateau, or a pool absent from
     * the previous scan resets that pool's streak to zero. The very first scan of a new scanner
     * instance has no previous sample to compare against, so it seeds the baseline and reports
     * {@link MemoryContext.BufferPoolTrend#unavailable()}.
     */
    private MemoryContext.BufferPoolTrend computeBufferPoolTrend(List<MemoryContext.BufferPoolSnapshot> pools) {
        Map<String, Long> currentUsed = new LinkedHashMap<>();
        for (MemoryContext.BufferPoolSnapshot pool : pools) {
            if (pool.used() >= 0) {
                currentUsed.put(pool.name(), pool.used());
            }
        }

        Map<String, Integer> streaks = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : currentUsed.entrySet()) {
            String name = entry.getKey();
            long used = entry.getValue();
            Long previous = previousBufferPoolUsed.get(name);
            if (previous == null) {
                continue;
            }
            int streak = used > previous ? incrementStreak(previousBufferPoolStreaks.getOrDefault(name, 0)) : 0;
            streaks.put(name, streak);
        }

        MemoryContext.BufferPoolTrend trend = previousBufferPoolSampleAvailable
                ? new MemoryContext.BufferPoolTrend(true, streaks)
                : MemoryContext.BufferPoolTrend.unavailable();

        previousBufferPoolUsed = currentUsed;
        previousBufferPoolStreaks = streaks;
        previousBufferPoolSampleAvailable = !currentUsed.isEmpty();
        return trend;
    }

    /**
     * Computes the post-histogram old-generation usage trend against the previous scan (MEM-HEAP-008's
     * occupancy-growth signal). Missing readings break consecutive evidence. The first valid sample seeds the baseline and
     * reports {@link MemoryContext.OldGenTrend#unavailable()}.
     */
    private MemoryContext.OldGenTrend computeOldGenTrend(MemoryContext.PostGcHeapData postGcHeap) {
        if (!postGcHeap.oldGenAvailable() || postGcHeap.oldGenUsed() < 0) {
            previousOldGenUsedBytes = -1;
            previousOldGenStreak = 0;
            previousOldGenSampleAvailable = false;
            return MemoryContext.OldGenTrend.unavailable();
        }
        long used = postGcHeap.oldGenUsed();
        MemoryContext.OldGenTrend trend;
        if (previousOldGenSampleAvailable) {
            int streak = used > previousOldGenUsedBytes ? incrementStreak(previousOldGenStreak) : 0;
            trend = new MemoryContext.OldGenTrend(true, streak, used);
            previousOldGenStreak = streak;
        } else {
            trend = MemoryContext.OldGenTrend.unavailable();
            previousOldGenStreak = 0;
        }
        previousOldGenUsedBytes = used;
        previousOldGenSampleAvailable = true;
        return trend;
    }

    private static int incrementStreak(int streak) {
        return streak == Integer.MAX_VALUE ? streak : streak + 1;
    }

    private void resetObservations() {
        previousGcSample = null;
        previousPostHistogramGcEvent = MemoryContext.GcEvent.unavailable();
        previousBufferPoolUsed = Map.of();
        previousBufferPoolStreaks = Map.of();
        previousBufferPoolSampleAvailable = false;
        previousOldGenUsedBytes = -1;
        previousOldGenStreak = 0;
        previousOldGenSampleAvailable = false;
    }

    private MemoryReport report(
            String status,
            String message,
            Long scannedAt,
            MemorySummaryDto summary,
            int rulesEvaluated,
            List<MemoryRuleResultDto> results) {
        return report(status, message, scannedAt, summary, rulesEvaluated, results, AdvisorEvidenceDto.unknown());
    }

    private MemoryReport report(
            String status,
            String message,
            Long scannedAt,
            MemorySummaryDto summary,
            int rulesEvaluated,
            List<MemoryRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
        List<MemoryRuleResultDto> violations = results.stream()
                .filter(MemoryScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
        int violationsFound = violations.size();
        MemoryScanStatusDto scan =
                new MemoryScanStatusDto(ANALYZER, status, message, scannedAt, rulesEvaluated, violationsFound);
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
                violationsFound);
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
                report.evidence());
    }

    static AdvisorEvidenceDto evidence(List<MemoryEvaluation> evaluations) {
        if (evaluations.isEmpty()) return AdvisorEvidenceDto.unknown();
        List<String> limitations = evaluations.stream()
                .filter(MemoryEvaluation::requiredUnknown)
                .map(evaluation ->
                        evaluation.result().id() + ": required JVM evidence was unavailable or evaluation failed.")
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
