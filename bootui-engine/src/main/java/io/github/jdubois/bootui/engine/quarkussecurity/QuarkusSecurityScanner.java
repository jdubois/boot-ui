package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<SecurityRuleResultDto> IMPORTANCE = Comparator.comparingInt(
                    (SecurityRuleResultDto r) -> SEVERITIES.indexOf(r.severity()))
            .thenComparing(r -> -r.violationCount())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<QuarkusSecuritySnapshot> snapshotSupplier;
    private final Clock clock;

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
        QuarkusSecuritySnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "ERROR", "Could not read Quarkus security configuration.", clock.millis(), List.of(), List.of());
        }
        List<SecurityRuleResultDto> violations = QuarkusSecurityChecks.evaluate(snap);
        List<String> policyLabels = snap.permissions().stream()
                .map(QuarkusSecurityScanner::policyLabel)
                .toList();
        return report("SCANNED", "Quarkus security configuration analysed.", clock.millis(), policyLabels, violations);
    }

    private static String policyLabel(QuarkusSecurityPermission p) {
        return p.name() + " → " + p.policy() + " (" + p.paths() + ")";
    }

    private SecurityReport report(
            String status, String message, Long scannedAt, List<String> policyLabels, List<SecurityRuleResultDto> raw) {
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
                List.of());
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
                report.analysisErrors());
    }

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : SEVERITIES) {
            counts.put(s, 0);
        }
        for (SecurityRuleResultDto r : results) {
            counts.computeIfPresent(r.severity(), (k, c) -> c + 1);
        }
        List<SecuritySeverityCountDto> out = new ArrayList<>();
        counts.forEach((sev, c) -> out.add(new SecuritySeverityCountDto(sev, c)));
        return out;
    }
}
