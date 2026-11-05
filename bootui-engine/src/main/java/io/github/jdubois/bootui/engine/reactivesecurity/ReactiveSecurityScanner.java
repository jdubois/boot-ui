package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAdvisorService;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAnalysisDetector;
import io.github.jdubois.bootui.engine.staticsecurity.StaticSecurityAnalysisRule;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
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
    private final Supplier<Path> projectRootSupplier;
    private final StaticSecurityAnalysisDetector staticSecurityDetector = new StaticSecurityAnalysisDetector();
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();
    private final AdvisorScanState<SecurityReport> state =
            new AdvisorScanState<>(SecurityReport::withViolationDetails, ReactiveSecurityScanner::ruleCatalog);

    private ReactiveSecurityScanner(
            Supplier<ReactiveSecurityObservation> observationSupplier,
            Clock clock,
            Supplier<Path> projectRootSupplier) {
        this.observationSupplier = observationSupplier;
        this.clock = clock;
        this.projectRootSupplier = projectRootSupplier;
    }

    /**
     * Builds a scanner from a live {@link ReactiveSecurityObservation} supplier and a clock. The
     * observation is collected once per {@link #scan()}; adapters own the actual bean/reflection
     * collection and must never block a reactive event-loop thread while doing so.
     */
    public static ReactiveSecurityScanner using(
            Supplier<ReactiveSecurityObservation> observationSupplier, Clock clock) {
        return new ReactiveSecurityScanner(observationSupplier, clock, () -> null);
    }

    /** Builds a scanner that also checks a bounded local project root after runtime rule evaluation. */
    public static ReactiveSecurityScanner using(
            Supplier<ReactiveSecurityObservation> observationSupplier,
            Clock clock,
            Supplier<Path> projectRootSupplier) {
        return new ReactiveSecurityScanner(observationSupplier, clock, projectRootSupplier);
    }

    /** Returns a placeholder report before the first scan has been triggered. */
    public SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security Advisor has not run yet. Run security checks in the panel, or call security_scan"
                        + " (bootui security scan), to inspect the filter chains.",
                null,
                0,
                0,
                List.of());
    }

    /** Performs the full scan and returns the result. */
    public SecurityReport scan() {
        return singleFlight.run(ActionOperations.SECURITY_SCAN, () -> {
            AdvisorViolationCollector collector = state.collector();
            return state.publish(doScan(collector), collector);
        });
    }

    public SecurityReport lastReport() {
        return state.currentReport(this::initialReport);
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return state.ruleViolations(ruleId, scanId, offset, limit);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        state.setRetentionLimit(limit);
    }

    private SecurityReport doScan(AdvisorViolationCollector collector) {
        ReactiveSecurityObservation observation = safeObservation();
        ReactiveSecurityContext context = ReactiveSecurityContext.from(observation, collector);
        if (context.chains().isEmpty()) {
            StaticSecurityAnalysisDetector.Inspection inspection = inspectStaticSecurity();
            boolean staticOnly = observation.errors().isEmpty();
            String analyzer = staticOnly ? StaticSecurityAdvisorService.ANALYZER : ANALYZER;
            String disclaimer = staticOnly ? StaticSecurityAdvisorService.DISCLAIMER : DISCLAIMER;
            String message = observation.errors().isEmpty()
                    ? "No Spring Security SecurityWebFilterChain beans were found to inspect."
                    : "Spring Security reactive configuration could not be read: "
                            + String.join("; ", observation.errors());
            if (inspection.state() == StaticSecurityAnalysisDetector.State.NOT_APPLICABLE) {
                return report(
                        analyzer,
                        disclaimer,
                        "DISABLED",
                        message,
                        clock.millis(),
                        0,
                        0,
                        List.of(),
                        AdvisorEvidenceDto.unknown(),
                        List.of());
            }
            List<SecurityRuleResultDto> staticFindings =
                    inspection.state() == StaticSecurityAnalysisDetector.State.NOT_CONFIGURED
                            ? List.of(StaticSecurityAnalysisRule.finding(collector))
                            : List.of();
            String staticMessage;
            if (inspection.state() == StaticSecurityAnalysisDetector.State.CONFIGURED) {
                staticMessage = "A recognized static analyzer is declared locally, but no Spring Security web filter "
                        + "chain was found to inspect. BootUI does not verify analyzer execution.";
            } else if (inspection.state() == StaticSecurityAnalysisDetector.State.NOT_CONFIGURED) {
                staticMessage = "No Spring Security web filter chain was found to inspect. No local static analyzer "
                        + "declaration was found.";
            } else {
                staticMessage = "No Spring Security web filter chain was found to inspect, and local static analyzer "
                        + "declarations could not be assessed.";
            }
            message = observation.errors().isEmpty() ? staticMessage : message + " " + staticMessage;
            AdvisorEvidenceDto evidence = AdvisorEvidenceDto.unknown();
            if (inspection.state() == StaticSecurityAnalysisDetector.State.INDETERMINATE) {
                evidence = StaticSecurityAnalysisRule.addAssessmentLimitation(evidence);
            }
            return report(
                    analyzer,
                    disclaimer,
                    "PARTIAL",
                    message,
                    clock.millis(),
                    0,
                    inspection.evaluated() ? 1 : 0,
                    staticFindings,
                    evidence,
                    List.of());
        }

        List<SecurityRuleResultDto> results = new ArrayList<>(ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList());
        int chains = context.chains().size();
        long inconclusive = results.stream()
                .filter(result -> "SKIPPED".equals(result.status()))
                .count();
        AdvisorEvidenceDto evidence = evidence(observation, context);
        StaticSecurityAnalysisDetector.Inspection inspection = inspectStaticSecurity();
        if (inspection.state() == StaticSecurityAnalysisDetector.State.NOT_CONFIGURED) {
            results.add(StaticSecurityAnalysisRule.finding(collector));
        } else if (inspection.state() == StaticSecurityAnalysisDetector.State.INDETERMINATE) {
            evidence = StaticSecurityAnalysisRule.addAssessmentLimitation(evidence);
        }
        String status = evidence.coverageComplete() ? "SCANNED" : "PARTIAL";
        String message = "Security Advisor completed against " + chains + " security web filter chain"
                + (chains == 1 ? "." : "s.");
        if (!observation.errors().isEmpty()) {
            message += " Some configuration could not be read: " + String.join("; ", observation.errors());
        }
        if (inconclusive > 0) {
            message += " " + inconclusive + " checks have incomplete or unsupported evidence.";
        }
        if (!analysisErrors(results).isEmpty()) {
            message += " Some checks failed; see analysis errors.";
        }
        if (inspection.state() == StaticSecurityAnalysisDetector.State.INDETERMINATE) {
            message += " Static analyzer configuration could not be assessed.";
        }
        int rulesEvaluated = ReactiveSecurityRuleRegistry.activeRules().size() + (inspection.evaluated() ? 1 : 0);
        return report(
                status, message, clock.millis(), chains, rulesEvaluated, results, evidence, chainDescriptions(context));
    }

    private StaticSecurityAnalysisDetector.Inspection inspectStaticSecurity() {
        try {
            return staticSecurityDetector.inspect(projectRootSupplier == null ? null : projectRootSupplier.get());
        } catch (RuntimeException | LinkageError ex) {
            return new StaticSecurityAnalysisDetector.Inspection(StaticSecurityAnalysisDetector.State.INDETERMINATE);
        }
    }

    private static List<String> ruleCatalog() {
        List<String> ids = new ArrayList<>(AdvisorScanState.ruleIds(
                ReactiveSecurityRuleRegistry.activeRules(),
                rule -> rule.definition().id()));
        ids.add(StaticSecurityAnalysisDetector.RULE_ID);
        return List.copyOf(ids);
    }

    private static AdvisorEvidenceDto evidence(
            ReactiveSecurityObservation observation, ReactiveSecurityContext context) {
        return context.evaluation()
                .evidence(
                        observation.errors().isEmpty()
                                ? List.of()
                                : List.of("Some reactive security observations could not be collected."));
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
                report.analysisErrors(),
                report.evidence(),
                report.violationDetails());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private ReactiveSecurityObservation safeObservation() {
        try {
            ReactiveSecurityObservation observation = observationSupplier.get();
            if (observation == null) {
                return emptyObservation("No reactive security observation is available.");
            }
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
        return report(
                status,
                message,
                scannedAt,
                filterChainsAnalyzed,
                rulesEvaluated,
                results,
                AdvisorEvidenceDto.unknown(),
                List.of());
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results,
            AdvisorEvidenceDto evidence,
            List<String> filterChains) {
        return report(
                ANALYZER,
                DISCLAIMER,
                status,
                message,
                scannedAt,
                filterChainsAnalyzed,
                rulesEvaluated,
                results,
                evidence,
                filterChains);
    }

    private SecurityReport report(
            String analyzer,
            String disclaimer,
            String status,
            String message,
            Long scannedAt,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results,
            AdvisorEvidenceDto evidence,
            List<String> filterChains) {
        List<SecurityRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                analyzer, status, message, scannedAt, rulesEvaluated, filterChainsAnalyzed, violationsFound);
        return new SecurityReport(
                true,
                disclaimer,
                filterChains,
                filterChainsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results),
                evidence);
    }

    private static List<String> chainDescriptions(ReactiveSecurityContext context) {
        if (context == null) {
            return List.of();
        }
        return context.chains().stream().map(WebFilterChainObservation::matcher).toList();
    }

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                SEVERITIES,
                results,
                ReactiveSecurityScanner::isViolation,
                SecurityRuleResultDto::severity,
                SecurityRuleResultDto::violationCount);
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
