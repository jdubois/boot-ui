package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import io.github.jdubois.bootui.core.dto.MemoryAdvisorReport;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorSeverityCountDto;
import io.github.jdubois.bootui.core.dto.MemoryAdvisorSummaryDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Memory Advisor.
 *
 * <p>The scanner aggregates the JVM data already produced by the Memory, Threads, and Heap Dump
 * panels into a {@link MemoryAdvisorContext} and runs a curated registry of static health rules.
 * It never suspends threads, never changes runtime configuration, and only reads management beans.
 * Collecting the heap-content histogram triggers a full GC, exactly like the Heap Dump panel's
 * analyze action; the rest of the snapshot is read cheaply.</p>
 */
final class MemoryAdvisorScanner {

    private static final String ANALYZER = "BootUI Memory Advisor";
    private static final String DISCLAIMER =
            "Heuristic JVM memory and thread health rules run against this process's live management beans only. "
                    + "These findings are review prompts, not verdicts, and should be validated against the "
                    + "application's workload and a profiler before acting.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private static final Comparator<MemoryAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (MemoryAdvisorRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(MemoryAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(MemoryAdvisorRuleResultDto::id);

    private final Supplier<MemoryAdvisorContext> contextSupplier;
    private final Clock clock;

    MemoryAdvisorScanner(MemoryAdvisorCollector collector, Clock clock) {
        this(collector::collect, clock);
    }

    MemoryAdvisorScanner(Supplier<MemoryAdvisorContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    MemoryAdvisorReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Memory Advisor has not run yet. Click Run memory checks to inspect the JVM runtime.",
                null,
                null,
                0,
                List.of());
    }

    MemoryAdvisorReport scan() {
        MemoryAdvisorContext context;
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

        List<MemoryAdvisorRuleResultDto> results = MemoryAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        boolean hadErrors = results.stream().anyMatch(result -> MemoryAdvisorRuleSupport.ERROR.equals(result.status()));
        String status = hadErrors ? "PARTIAL" : "SCANNED";
        String message = "Memory Advisor evaluated " + results.size() + " rules against the JVM runtime.";
        if (hadErrors) {
            message += " Some rules could not be evaluated.";
        }
        return report(status, message, clock.millis(), summary(context), results.size(), results);
    }

    private MemoryAdvisorReport report(
            String status,
            String message,
            Long scannedAt,
            MemoryAdvisorSummaryDto summary,
            int rulesEvaluated,
            List<MemoryAdvisorRuleResultDto> results) {
        List<MemoryAdvisorRuleResultDto> violations = results.stream()
                .filter(MemoryAdvisorScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
        int violationsFound = violations.size();
        MemoryAdvisorScanStatusDto scan =
                new MemoryAdvisorScanStatusDto(ANALYZER, status, message, scannedAt, rulesEvaluated, violationsFound);
        return new MemoryAdvisorReport(
                true,
                DISCLAIMER,
                rulesEvaluated,
                violationsFound,
                summary,
                severityCounts(violations),
                scan,
                violations);
    }

    private MemoryAdvisorSummaryDto summary(MemoryAdvisorContext context) {
        return new MemoryAdvisorSummaryDto(
                context.memory().heapUsed(),
                context.memory().heapMax(),
                context.heapUsedPercent(),
                context.threads().total(),
                context.threads().peak(),
                context.threads().deadlockDetected(),
                context.classLoading().loadedClasses(),
                context.heapContent().available());
    }

    private List<MemoryAdvisorSeverityCountDto> severityCounts(List<MemoryAdvisorRuleResultDto> violations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (MemoryAdvisorRuleResultDto result : violations) {
            counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new MemoryAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(MemoryAdvisorRuleResultDto result) {
        return MemoryAdvisorRuleSupport.VIOLATION.equals(result.status());
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }
}
