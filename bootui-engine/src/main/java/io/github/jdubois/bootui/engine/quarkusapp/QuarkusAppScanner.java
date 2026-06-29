package io.github.jdubois.bootui.engine.quarkusapp;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quarkus-native application advisor scanner. The Quarkus replacement for the Spring {@code SpringScanner}:
 * it shares the {@link SpringReport} DTO (so the panel and UI are identical) but evaluates a framework-specific
 * ruleset over a neutral {@link QuarkusAppSnapshot} (CDI scope hygiene, build-time config, reactive idioms,
 * profiles, dev services). The catalogue lives in {@code docs/QUARKUS-CHECKS.md}. Framework-free: depends only
 * on core DTOs and the SPI carrier.
 */
public final class QuarkusAppScanner {

    static final String ANALYZER = "BootUI Quarkus advisor";
    private static final String DISCLAIMER =
            "Heuristic local checks against the Quarkus application idioms (CDI scopes, MicroProfile config, "
                    + "reactive vs blocking, profiles). Review prompts only; not a substitute for a manual review.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<SpringRuleResultDto> IMPORTANCE = Comparator.comparingInt(
                    (SpringRuleResultDto r) -> SEVERITIES.indexOf(r.severity()))
            .thenComparing(r -> -r.violationCount())
            .thenComparing(SpringRuleResultDto::id);

    private final Supplier<QuarkusAppSnapshot> snapshotSupplier;
    private final Clock clock;

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
                List.of());
    }

    public SpringReport scan() {
        QuarkusAppSnapshot snap;
        try {
            snap = snapshotSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "ERROR", "Could not read Quarkus application idioms.", clock.millis(), List.of(), 0, List.of());
        }
        List<SpringRuleResultDto> violations = QuarkusAppChecks.evaluate(snap);
        List<String> inspected = inspected(snap);
        return report(
                "SCANNED",
                "Quarkus application idioms analysed.",
                clock.millis(),
                inspected,
                snap.beanCount(),
                violations);
    }

    private static List<String> inspected(QuarkusAppSnapshot s) {
        List<String> out = new ArrayList<>();
        out.add(s.beanCount() + " managed beans");
        out.add(s.endpointCount() + " JAX-RS endpoints");
        out.add(s.configPropertyCount() + " @ConfigProperty sites");
        if (!s.activeProfiles().isEmpty()) {
            out.add("profiles: " + String.join(",", s.activeProfiles()));
        }
        return out;
    }

    private SpringReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> inspected,
            int componentsAnalyzed,
            List<SpringRuleResultDto> raw) {
        List<SpringRuleResultDto> violations = raw.stream().sorted(IMPORTANCE).toList();
        SpringScanStatusDto scan = new SpringScanStatusDto(
                ANALYZER,
                status,
                message,
                scannedAt,
                QuarkusAppChecks.ruleCount(),
                componentsAnalyzed,
                violations.size());
        return new SpringReport(
                true,
                DISCLAIMER,
                inspected,
                componentsAnalyzed,
                QuarkusAppChecks.ruleCount(),
                violations.size(),
                severityCounts(violations),
                scan,
                violations,
                List.of());
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
                report.analysisErrors());
    }

    private List<SpringSeverityCountDto> severityCounts(List<SpringRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : SEVERITIES) {
            counts.put(s, 0);
        }
        for (SpringRuleResultDto r : results) {
            counts.computeIfPresent(r.severity(), (k, c) -> c + 1);
        }
        List<SpringSeverityCountDto> out = new ArrayList<>();
        counts.forEach((sev, c) -> out.add(new SpringSeverityCountDto(sev, c)));
        return out;
    }
}
