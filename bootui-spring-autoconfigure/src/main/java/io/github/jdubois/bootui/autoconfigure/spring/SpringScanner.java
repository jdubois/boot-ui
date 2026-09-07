package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/** Explicit, single-flight, read-only inspection. Discovery never initializes application beans. */
final class SpringScanner {
    private static final String ANALYZER = "BootUI Spring Advisor";
    private static final String DISCLAIMER =
            "Bounded Spring metadata and configuration checks are review prompts, not runtime or deployment verdicts. "
                    + "Unknown observations are unevaluated; validate advice against the application's requirements.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<SpringRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SpringRuleResultDto result) -> SEVERITIES.indexOf(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(SpringRuleResultDto::violationCount).reversed())
            .thenComparing(SpringRuleResultDto::id);
    private final Supplier<SpringContext> contextSupplier;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    SpringScanner(ConfigurableListableBeanFactory beanFactory, Environment environment, boolean reactive, Clock clock) {
        this(() -> SpringInventory.discover(beanFactory, environment, reactive), clock);
    }

    SpringScanner(SpringContext context, Clock clock) {
        this(() -> context, clock);
    }

    private SpringScanner(Supplier<SpringContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    SpringReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Spring Advisor has not run yet. Click Run Spring checks to inspect the application context.",
                null,
                List.of(),
                0,
                List.of());
    }

    SpringReport scan() {
        return singleFlight.run(ActionOperations.SPRING_SCAN, this::doScan);
    }

    private SpringReport doScan() {
        SpringContext context;
        try {
            context = contextSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "DISABLED",
                    "The application context could not be inspected safely.",
                    clock.millis(),
                    List.of("Context inspection failed; no rules evaluated."),
                    0,
                    List.of());
        }
        if (context == null) {
            return report(
                    "DISABLED",
                    "No application context was available to inspect.",
                    clock.millis(),
                    List.of(),
                    0,
                    List.of());
        }
        List<SpringRuleResultDto> results = SpringRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        List<String> inspected = new ArrayList<>();
        inspected.add("Bean definitions: " + context.beanDefinitionCount() + "; bounded, non-eager metadata only.");
        inspected.add("Configuration is not proof of execution, authorization, or network reachability.");
        long skipped = results.stream()
                .filter(result -> SpringRuleSupport.SKIPPED.equals(result.status()))
                .count();
        inspected.add("Unevaluated rules (inapplicable or required evidence unavailable): " + skipped + ".");
        results.stream()
                .filter(result -> SpringRuleSupport.SKIPPED.equals(result.status()))
                .limit(38)
                .forEach(result -> inspected.add(
                        result.id() + ": " + result.sampleViolations().get(0)));
        inspected.addAll(context.observations().incomplete());
        return report(
                "SCANNED",
                "Spring Advisor completed against " + context.beanDefinitionCount()
                        + " bean definition(s); see unevaluated observations and analysis errors.",
                clock.millis(),
                inspected,
                context.beanDefinitionCount(),
                results);
    }

    private SpringReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> inspected,
            int componentsAnalyzed,
            List<SpringRuleResultDto> results) {
        List<SpringRuleResultDto> violations = results.stream()
                .filter(result -> SpringRuleSupport.VIOLATION.equals(result.status()))
                .sorted(IMPORTANCE_ORDER)
                .toList();
        SpringScanStatusDto scan = new SpringScanStatusDto(
                ANALYZER, status, message, scannedAt, results.size(), componentsAnalyzed, violations.size());
        return new SpringReport(
                true,
                DISCLAIMER,
                List.copyOf(inspected),
                componentsAnalyzed,
                results.size(),
                violations.size(),
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results));
    }

    SpringReport applyDismissals(SpringReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) return report;
        List<SpringRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<SpringRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        SpringScanStatusDto scan = report.scan();
        return new SpringReport(
                report.localOnly(),
                report.disclaimer(),
                report.inspected(),
                report.componentsAnalyzed(),
                report.rulesEvaluated(),
                active.size(),
                severityCounts(active),
                new SpringScanStatusDto(
                        scan.analyzer(),
                        scan.status(),
                        scan.message(),
                        scan.scannedAt(),
                        scan.rulesEvaluated(),
                        scan.componentsAnalyzed(),
                        active.size()),
                marked,
                report.analysisErrors());
    }

    static List<SpringRuleResultDto> analysisErrors(List<SpringRuleResultDto> results) {
        return results.stream()
                .filter(result -> SpringRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(SpringRuleResultDto::id))
                .toList();
    }

    private List<SpringSeverityCountDto> severityCounts(List<SpringRuleResultDto> results) {
        return SeverityOrder.occurrenceCounts(
                        SEVERITIES,
                        results,
                        ignored -> true,
                        SpringRuleResultDto::severity,
                        SpringRuleResultDto::violationCount)
                .entrySet()
                .stream()
                .map(entry -> new SpringSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
