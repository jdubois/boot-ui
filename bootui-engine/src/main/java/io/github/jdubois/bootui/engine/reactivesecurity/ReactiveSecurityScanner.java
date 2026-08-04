package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand WebFlux-native (reactive) Spring Security advisor.
 *
 * <p>The scanner runs a curated registry of static best-practice checks against a passive,
 * framework-neutral {@link ReactiveSecurityObservation} — the application's registered
 * {@code SecurityWebFilterChain} beans (collected by the Spring adapter, which excludes BootUI's own
 * chain), CORS/OAuth2 facts, and a precomputed environment snapshot. It never intercepts live requests
 * and never surfaces credentials, keys, or session identifiers; the {@link ReactiveSecurityEnvironmentSnapshot}
 * carries suspected-secret property <em>keys</em> only, never values.</p>
 *
 * <p>This advisor evaluates the application's reactive security configuration against a best-practice
 * ruleset; it is distinct in scope from the raw Spring Security panel, which renders the same filter
 * chains as-is with no rule evaluation.</p>
 */
public final class ReactiveSecurityScanner {

    private static final String ANALYZER = "BootUI Spring Security Advisor (Reactive)";
    private static final String DISCLAIMER =
            "Heuristic Spring Security WebFlux rules run against the host application's registered "
                    + "security web filter chains and security beans only. These checks are review prompts, "
                    + "not verdicts, and should be validated against the application's threat model.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private static final Comparator<SecurityRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SecurityRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(SecurityRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<ReactiveSecurityObservation> observationSupplier;
    private final Clock clock;

    private volatile ReactiveSecurityContext lastContext;

    private ReactiveSecurityScanner(Supplier<ReactiveSecurityObservation> observationSupplier, Clock clock) {
        this.observationSupplier = observationSupplier;
        this.clock = clock;
    }

    /**
     * Builds a scanner from a live {@link ReactiveSecurityObservation} supplier and a clock. The
     * observation is collected once per {@link #scan()}; adapters own the actual bean/reflection
     * collection and must never block a reactive event-loop thread while doing so.
     */
    public static ReactiveSecurityScanner using(
            Supplier<ReactiveSecurityObservation> observationSupplier, Clock clock) {
        return new ReactiveSecurityScanner(observationSupplier, clock);
    }

    /** Returns a placeholder report before the first scan has been triggered. */
    public SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security Advisor has not run yet. Click Run security checks to inspect the filter chains.",
                null,
                0,
                0,
                List.of());
    }

    /** Performs the full scan and returns the result. */
    public SecurityReport scan() {
        ReactiveSecurityObservation observation = safeObservation();
        ReactiveSecurityContext context = ReactiveSecurityContext.from(observation);
        if (context.chains().isEmpty()) {
            String message = observation.errors().isEmpty()
                    ? "No Spring Security SecurityWebFilterChain beans were found to inspect."
                    : "Spring Security reactive configuration could not be read: "
                            + String.join("; ", observation.errors());
            return report("DISABLED", message, clock.millis(), 0, 0, List.of());
        }

        List<SecurityRuleResultDto> results = ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        int chains = context.chains().size();
        String status = observation.errors().isEmpty() ? "SCANNED" : "PARTIAL";
        String message = "Security Advisor completed against " + chains + " security web filter chain"
                + (chains == 1 ? "." : "s.");
        if (!observation.errors().isEmpty()) {
            message += " Some configuration could not be read: " + String.join("; ", observation.errors());
        }
        return report(status, message, clock.millis(), chains, results.size(), results);
    }

    /** Applies dismissals to the given report and returns the updated report. */
    public SecurityReport applyDismissals(SecurityReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SecurityRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<SecurityRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        SecurityScanStatusDto scan = report.scan();
        SecurityScanStatusDto updatedScan = new SecurityScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.filterChainsAnalyzed(),
                violationsFound);
        return new SecurityReport(
                report.localOnly(),
                report.disclaimer(),
                report.filterChains(),
                report.filterChainsAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked,
                report.analysisErrors());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private ReactiveSecurityObservation safeObservation() {
        try {
            ReactiveSecurityObservation observation = observationSupplier.get();
            if (observation == null) {
                return emptyObservation("No reactive security observation is available.");
            }
            lastContext = ReactiveSecurityContext.from(observation);
            return observation;
        } catch (RuntimeException | LinkageError ex) {
            return emptyObservation(safeMessage(ex));
        }
    }

    private static ReactiveSecurityObservation emptyObservation(String reason) {
        return new ReactiveSecurityObservation(
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of(reason == null ? "Unavailable." : reason));
    }

    private static String safeMessage(Throwable ex) {
        return ex.getClass().getName();
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results) {
        List<SecurityRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, filterChainsAnalyzed, violationsFound);
        return new SecurityReport(
                true,
                DISCLAIMER,
                chainDescriptions(lastContext),
                filterChainsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results));
    }

    private static List<String> chainDescriptions(ReactiveSecurityContext context) {
        if (context == null) {
            return List.of();
        }
        return context.chains().stream().map(WebFilterChainObservation::matcher).toList();
    }

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (SecurityRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SecuritySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SecurityRuleResultDto> violationResults(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(ReactiveSecurityScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    static List<SecurityRuleResultDto> analysisErrors(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(result -> ReactiveSecuritySupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(SecurityRuleResultDto::id))
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(SecurityRuleResultDto result) {
        return ReactiveSecuritySupport.VIOLATION.equals(result.status());
    }
}
