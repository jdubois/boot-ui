package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.AdvisorAssessmentEvidenceDto;
import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quarkus-native application advisor scanner. The Quarkus replacement for the Spring {@code SpringScanner}:
 * it shares the {@link SpringReport} DTO (so the panel and UI are identical) but evaluates a framework-specific
 * ruleset over a neutral {@link QuarkusAppSnapshot}. The catalogue lives in
 * {@code docs/QUARKUS-ADVISOR-CHECKS.md}. Framework-free: depends only
 * on core DTOs and the SPI carrier.
 */
public final class QuarkusAppScanner {

    static final String ANALYZER = "BootUI Quarkus advisor";
    private static final String DISCLAIMER =
            "Evidence-based local review prompts for Quarkus application declarations and observed configuration."
                    + " Not proof of a deployed production setting, runtime defect, or complete application review.";
    private static final Comparator<SpringRuleResultDto> IMPORTANCE = Comparator.comparingInt(
                    (SpringRuleResultDto r) -> SeverityOrder.rank(r.severity()))
            .thenComparing(r -> -r.violationCount())
            .thenComparing(SpringRuleResultDto::id);

    private final Supplier<QuarkusAppSnapshot> snapshotSupplier;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    private QuarkusAppScanner(Supplier<QuarkusAppSnapshot> snapshotSupplier, Clock clock) {
        this.snapshotSupplier = snapshotSupplier;
        this.clock = clock;
    }

    public static QuarkusAppScanner usingSnapshot(Supplier<QuarkusAppSnapshot> supplier, Clock clock) {
        return new QuarkusAppScanner(supplier, clock);
    }

    public SpringReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Quarkus checks have not run yet. Click Run Quarkus checks to evaluate the application.",
                null,
                List.of(),
                0,
                0,
                List.of(),
                List.of());
    }

    public SpringReport scan() {
        return singleFlight.run(ActionOperations.SPRING_SCAN, this::doScan);
    }

    private SpringReport doScan() {
        QuarkusAppSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            snap = null;
        }
        QuarkusAppChecks.Evaluation evaluation = QuarkusAppChecks.evaluate(snap);
        String status =
                evaluation.errors().isEmpty() ? "SCANNED" : evaluation.evidenceInspected() ? "PARTIAL" : "ERROR";
        String message =
                switch (status) {
                    case "SCANNED" -> "Quarkus application evidence analysed.";
                    case "PARTIAL" ->
                        "Quarkus checks are incomplete. Available findings are retained; review evidence errors.";
                    default -> "Could not inspect Quarkus application evidence.";
                };
        return report(
                status,
                message,
                clock.millis(),
                inspected(snap),
                snap == null || snap.metadata() == null ? 0 : snap.metadata().beanCount(),
                evaluation.rulesEvaluated(),
                evaluation.findings(),
                evaluation.errors());
    }

    private static List<String> inspected(QuarkusAppSnapshot s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        QuarkusAppMetadata metadata = s.metadata();
        if (metadata != null && metadata.available()) {
            out.add(metadata.beanCount() + " resolved application class beans");
            out.add(metadata.endpointCount() + " registered REST endpoints");
            out.add(metadata.configPropertyCount() + " @ConfigProperty sites");
            out.add(metadata.configMappingCount() + " configuration mappings");
            out.add(metadata.scheduledDeclarationCount() + " scheduled declarations (not an active job count)");
        }
        if (!s.activeProfiles().isEmpty()) {
            out.add(s.activeProfiles().size() + " active profiles");
        }
        return out;
    }

    private SpringReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> inspected,
            int componentsAnalyzed,
            int rulesEvaluated,
            List<SpringRuleResultDto> raw,
            List<SpringRuleResultDto> errors) {
        List<SpringRuleResultDto> violations = raw.stream().sorted(IMPORTANCE).toList();
        SpringScanStatusDto scan = new SpringScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, componentsAnalyzed, violations.size());
        return new SpringReport(
                true,
                DISCLAIMER,
                inspected,
                componentsAnalyzed,
                rulesEvaluated,
                violations.size(),
                severityCounts(violations),
                scan,
                violations,
                errors,
                new AdvisorAssessmentEvidenceDto(
                        rulesEvaluated > 0 || !violations.isEmpty(), "PARTIAL".equals(status) || !errors.isEmpty()));
    }

    public SpringReport applyDismissals(SpringReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SpringRuleResultDto> marked = report.results().stream()
                .map(r -> r.withDismissed(dismissedIds.contains(r.id())))
                .toList();
        List<SpringRuleResultDto> active =
                marked.stream().filter(r -> !r.dismissed()).toList();
        SpringScanStatusDto s = report.scan();
        SpringScanStatusDto scan = new SpringScanStatusDto(
                s.analyzer(),
                s.status(),
                s.message(),
                s.scannedAt(),
                s.rulesEvaluated(),
                s.componentsAnalyzed(),
                active.size());
        return new SpringReport(
                report.localOnly(),
                report.disclaimer(),
                report.inspected(),
                report.componentsAnalyzed(),
                report.rulesEvaluated(),
                active.size(),
                severityCounts(active),
                scan,
                marked,
                report.analysisErrors(),
                report.assessmentEvidence());
    }

    private List<SpringSeverityCountDto> severityCounts(List<SpringRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results, SpringRuleResultDto::severity, SpringRuleResultDto::violationCount);
        return counts.entrySet().stream()
                .map(entry -> new SpringSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
