package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemoryScanStatusDto;
import io.github.jdubois.bootui.core.dto.MemorySeverityCountDto;
import io.github.jdubois.bootui.core.dto.MemorySummaryDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Memory Advisor.
 *
 * <p>The scanner aggregates the JVM data already produced by the Memory, Threads, and Heap Dump
 * panels into a {@link MemoryContext} and runs a curated registry of static health rules.
 * It never suspends threads, never changes runtime configuration, and only reads management beans.
 * Collecting the heap-content histogram triggers a full GC, exactly like the Heap Dump panel's
 * analyze action; the rest of the snapshot is read cheaply.</p>
 */
final class MemoryScanner {

    private static final String ANALYZER = "BootUI Memory Advisor";
    private static final String DISCLAIMER =
            "Heuristic JVM memory and thread health rules run against this process's live management beans only. "
                    + "These findings are review prompts, not verdicts, and should be validated against the "
                    + "application's workload and a profiler before acting.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private static final Comparator<MemoryRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (MemoryRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(MemoryRuleResultDto::violationCount).reversed())
            .thenComparing(MemoryRuleResultDto::id);

    private final Supplier<MemoryContext> contextSupplier;
    private final Clock clock;

    /**
     * Post-histogram GC counters from the previous scan, used as the lower bound of the recent-GC
     * window. Guarded by the {@code synchronized} {@link #scan()} monitor.
     */
    private MemoryContext.GcSample previousGcSample;

    MemoryScanner(MemoryCollector collector, Clock clock) {
        this(collector::collect, clock);
    }

    MemoryScanner(Supplier<MemoryContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    MemoryReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Memory Advisor has not run yet. Click Run memory checks to inspect the JVM runtime.",
                null,
                null,
                0,
                List.of());
    }

    synchronized MemoryReport scan() {
        MemoryContext context;
        try {
            context = contextSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "ERROR",
                    "Memory Advisor could not read the JVM runtime: " + safeMessage(ex),
                    clock.millis(),
                    null,
                    0,
                    List.of());
        }

        MemoryContext.GcSample currentSample = MemoryContext.GcSample.from(context.runtime());
        MemoryContext.GcTrend trend = previousGcSample == null
                ? MemoryContext.GcTrend.unavailable()
                : MemoryContext.GcTrend.between(previousGcSample, context.preHistogramGc());
        previousGcSample = currentSample;
        MemoryContext evaluated = context.withGcTrend(trend);

        List<MemoryRuleResultDto> results = MemoryRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(evaluated))
                .toList();
        boolean hadErrors = results.stream().anyMatch(result -> MemoryRuleSupport.ERROR.equals(result.status()));
        String status = hadErrors ? "PARTIAL" : "SCANNED";
        String message = "Memory Advisor evaluated " + results.size() + " rules against the JVM runtime.";
        if (hadErrors) {
            message += " Some rules could not be evaluated.";
        }
        return report(status, message, clock.millis(), summary(evaluated), results.size(), results);
    }

    private MemoryReport report(
            String status,
            String message,
            Long scannedAt,
            MemorySummaryDto summary,
            int rulesEvaluated,
            List<MemoryRuleResultDto> results) {
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
                analysisErrors(results));
    }

    MemoryReport applyDismissals(MemoryReport report, Set<String> dismissedIds) {
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
                report.analysisErrors());
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
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (MemoryRuleResultDto result : violations) {
            counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new MemorySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(MemoryRuleResultDto result) {
        return MemoryRuleSupport.VIOLATION.equals(result.status());
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }
}
