package io.github.jdubois.bootui.engine.staticsecurity;

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
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Static-only Security Advisor used when a Spring application has no Spring Security integration. */
public final class StaticSecurityAdvisorService {

    public static final String ANALYZER = "BootUI Static Security Advisor";
    public static final String DISCLAIMER =
            "This limited check inspects local analyzer configuration only. It does not assess runtime security or "
                    + "verify that a configured analyzer ran.";

    private final Supplier<Path> projectRoot;
    private final Clock clock;
    private final StaticSecurityAnalysisDetector detector = new StaticSecurityAnalysisDetector();
    private final SingleFlightAction singleFlight = new SingleFlightAction();
    private final AdvisorScanState<SecurityReport> state = new AdvisorScanState<>(
            SecurityReport::withViolationDetails, () -> List.of(StaticSecurityAnalysisDetector.RULE_ID));

    public StaticSecurityAdvisorService(Supplier<Path> projectRoot, Clock clock) {
        this.projectRoot = projectRoot;
        this.clock = clock;
    }

    public SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security Advisor has not run yet. Run security checks to inspect the local analyzer configuration.",
                null,
                0,
                List.of(),
                AdvisorEvidenceDto.unknown());
    }

    public SecurityReport report() {
        return state.currentReport(this::initialReport);
    }

    public SecurityReport applyDismissals(SecurityReport report, java.util.Set<String> dismissedIds) {
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

    public SecurityReport scan() {
        return singleFlight.run(ActionOperations.SECURITY_SCAN, () -> {
            AdvisorViolationCollector collector = state.collector();
            return state.publish(doScan(collector), collector);
        });
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return state.ruleViolations(ruleId, scanId, offset, limit);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        state.setRetentionLimit(limit);
    }

    private SecurityReport doScan(AdvisorViolationCollector collector) {
        StaticSecurityAnalysisDetector.Inspection inspection;
        try {
            inspection = detector.inspect(projectRoot == null ? null : projectRoot.get());
        } catch (RuntimeException | LinkageError ex) {
            inspection =
                    new StaticSecurityAnalysisDetector.Inspection(StaticSecurityAnalysisDetector.State.INDETERMINATE);
        }
        return switch (inspection.state()) {
            case NOT_APPLICABLE ->
                report(
                        "DISABLED",
                        "No supported build file was found; static analyzer configuration was not assessed.",
                        clock.millis(),
                        0,
                        List.of(),
                        AdvisorEvidenceDto.unknown());
            case INDETERMINATE ->
                report(
                        "PARTIAL",
                        "Static analyzer configuration could not be assessed from bounded local files.",
                        clock.millis(),
                        0,
                        List.of(),
                        StaticSecurityAnalysisRule.addAssessmentLimitation(AdvisorEvidenceDto.unknown()));
            case CONFIGURED ->
                report(
                        "PARTIAL",
                        "A recognized static analyzer is declared. BootUI does not verify that it ran; runtime security "
                                + "was not assessed.",
                        clock.millis(),
                        1,
                        List.of(),
                        AdvisorEvidenceDto.unknown());
            case NOT_CONFIGURED -> {
                SecurityRuleResultDto finding = StaticSecurityAnalysisRule.finding(collector);
                yield report(
                        "PARTIAL",
                        "Static analyzer configuration was not found. Runtime security was not assessed.",
                        clock.millis(),
                        1,
                        List.of(finding),
                        AdvisorEvidenceDto.unknown());
            }
        };
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
        int violationsFound = results.size();
        List<SecuritySeverityCountDto> severityCounts = severityCounts(results);
        SecurityScanStatusDto scan =
                new SecurityScanStatusDto(ANALYZER, status, message, scannedAt, rulesEvaluated, 0, violationsFound);
        return new SecurityReport(
                true,
                DISCLAIMER,
                List.of(),
                0,
                rulesEvaluated,
                violationsFound,
                severityCounts,
                scan,
                results,
                List.of(),
                evidence);
    }

    private static List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        return SeverityOrder.occurrenceCounts(
                        List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"),
                        results,
                        ignored -> true,
                        SecurityRuleResultDto::severity,
                        SecurityRuleResultDto::violationCount)
                .entrySet()
                .stream()
                .map(entry -> new SecuritySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
