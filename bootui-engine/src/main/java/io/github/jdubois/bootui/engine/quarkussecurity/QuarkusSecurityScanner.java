package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quarkus-native Security advisor scanner. The Quarkus replacement for the Spring {@code SecurityScanner}:
 * it shares the {@link SecurityReport} DTO (so the panel and UI are identical) but evaluates a
 * framework-specific ruleset over a neutral {@link QuarkusSecuritySnapshot} (Elytron/OIDC, HTTP auth
 * permissions, TLS, CORS, headers, dev exposure, config hygiene). The catalogue lives in
 * {@code docs/QUARKUS-CHECKS.md}. Framework-free: depends only on core DTOs and the SPI carrier.
 */
public final class QuarkusSecurityScanner {

    static final String ANALYZER = "BootUI Quarkus security advisor";
    private static final String DISCLAIMER =
            "Heuristic local checks against the Quarkus security configuration (HTTP auth, OIDC/JWT, TLS, CORS, "
                    + "headers) and authorization annotations. Review prompts only; not a substitute for a manual review.";
    private static final Comparator<SecurityRuleResultDto> IMPORTANCE = Comparator.comparingInt(
                    (SecurityRuleResultDto r) -> SeverityOrder.rank(r.severity()))
            .thenComparing(r -> -r.violationCount())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<QuarkusSecuritySnapshot> snapshotSupplier;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    private QuarkusSecurityScanner(Supplier<QuarkusSecuritySnapshot> snapshotSupplier, Clock clock) {
        this.snapshotSupplier = snapshotSupplier;
        this.clock = clock;
    }

    public static QuarkusSecurityScanner usingSnapshot(Supplier<QuarkusSecuritySnapshot> supplier, Clock clock) {
        return new QuarkusSecurityScanner(supplier, clock);
    }

    public SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security checks have not run yet. Click Run security checks to evaluate the Quarkus configuration.",
                null,
                List.of(),
                List.of());
    }

    public SecurityReport scan() {
        return singleFlight.run(ActionOperations.SECURITY_SCAN, this::doScan);
    }

    private SecurityReport doScan() {
        QuarkusSecuritySnapshot snap;
        try {
            snap = snapshotSupplier.get();
            if (snap == null) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "ERROR",
                    "Could not read Quarkus security configuration.",
                    clock.millis(),
                    List.of(),
                    List.of(),
                    List.of(error("Quarkus security configuration could not be read.")));
        }
        QuarkusSecurityChecks.Evaluation evaluation = QuarkusSecurityChecks.evaluateObserved(snap);
        List<SecurityRuleResultDto> violations = evaluation.findings();
        List<String> policyLabels = snap.permissions().stream()
                .map(QuarkusSecurityScanner::policyLabel)
                .toList();
        List<SecurityRuleResultDto> errors = snap.evidence().failures().stream()
                .map(QuarkusSecurityScanner::error)
                .toList();
        boolean partial = !evaluation.evidence().coverageComplete();
        return report(
                partial ? "PARTIAL" : "SCANNED",
                partial
                        ? "Known Quarkus security declarations analysed; unsupported or unreadable observations remain incomplete."
                        : "Quarkus security configuration analysed.",
                clock.millis(),
                policyLabels,
                violations,
                errors,
                evaluation.evidence());
    }

    private static String policyLabel(QuarkusSecurityPermission p) {
        return "HTTP permission declaration → " + (p.knownPolicy() ? "supported policy" : "custom policy");
    }

    private SecurityReport report(
            String status, String message, Long scannedAt, List<String> policyLabels, List<SecurityRuleResultDto> raw) {
        return report(status, message, scannedAt, policyLabels, raw, List.of());
    }

    private static SecurityRuleResultDto error(String message) {
        return new SecurityRuleResultDto(
                "QS-ANALYSIS",
                "Quarkus security observation failed",
                "Analysis",
                "INFO",
                message,
                "ERROR",
                0,
                List.of(),
                "Review local configuration and retry the explicit scan.",
                "");
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> policyLabels,
            List<SecurityRuleResultDto> raw,
            List<SecurityRuleResultDto> errors) {
        return report(status, message, scannedAt, policyLabels, raw, errors, AdvisorEvidenceDto.unknown());
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> policyLabels,
            List<SecurityRuleResultDto> raw,
            List<SecurityRuleResultDto> errors,
            AdvisorEvidenceDto evidence) {
        List<SecurityRuleResultDto> violations = raw.stream().sorted(IMPORTANCE).toList();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                ANALYZER,
                status,
                message,
                scannedAt,
                QuarkusSecurityChecks.ruleCount(),
                policyLabels.size(),
                violations.size());
        return new SecurityReport(
                true,
                DISCLAIMER,
                policyLabels,
                policyLabels.size(),
                QuarkusSecurityChecks.ruleCount(),
                violations.size(),
                severityCounts(violations),
                scan,
                violations,
                errors,
                evidence);
    }

    public SecurityReport applyDismissals(SecurityReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SecurityRuleResultDto> marked = report.results().stream()
                .map(r -> r.withDismissed(dismissedIds.contains(r.id())))
                .toList();
        List<SecurityRuleResultDto> active =
                marked.stream().filter(r -> !r.dismissed()).toList();
        SecurityScanStatusDto s = report.scan();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                s.analyzer(),
                s.status(),
                s.message(),
                s.scannedAt(),
                s.rulesEvaluated(),
                s.filterChainsAnalyzed(),
                active.size());
        return new SecurityReport(
                report.localOnly(),
                report.disclaimer(),
                report.filterChains(),
                report.filterChainsAnalyzed(),
                report.rulesEvaluated(),
                active.size(),
                severityCounts(active),
                scan,
                marked,
                report.analysisErrors(),
                report.evidence());
    }

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results, SecurityRuleResultDto::severity, SecurityRuleResultDto::violationCount);
        return counts.entrySet().stream()
                .map(entry -> new SecuritySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
