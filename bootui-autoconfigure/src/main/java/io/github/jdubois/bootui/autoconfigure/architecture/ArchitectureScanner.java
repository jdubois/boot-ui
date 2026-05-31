package io.github.jdubois.bootui.autoconfigure.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureReport;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureScanStatusDto;
import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureSeverityCountDto;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded, on-demand ArchUnit hygiene scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages) and runs a fixed registry of curated, project-
 * agnostic architecture rules. Results are heuristic review prompts, not architectural verdicts.</p>
 */
final class ArchitectureScanner {

    private static final String ANALYZER = "BootUI ArchUnit hygiene";
    private static final String DISCLAIMER =
            "Heuristic, project-agnostic architecture rules run against the host application's own classes only. "
                    + "These checks complement, but do not replace, a project-specific ArchUnit test suite or an "
                    + "architecture review.";
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");

    private final Supplier<List<String>> basePackagesSupplier;
    private final ArchitectureClassImporter importer;
    private final Clock clock;

    ArchitectureScanner(Supplier<List<String>> basePackagesSupplier, ArchitectureClassImporter importer, Clock clock) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.clock = clock;
    }

    ArchitectureReport initialReport() {
        List<String> basePackages = safeBasePackages();
        return report(
                "NOT_SCANNED",
                "Architecture rules have not run yet. Click Run architecture checks to analyse the application classes.",
                null,
                basePackages,
                0,
                0,
                List.of());
    }

    ArchitectureReport scan() {
        List<String> basePackages = safeBasePackages();
        if (basePackages.isEmpty()) {
            return report(
                    "SCANNED",
                    "No application base package was detected, so there were no classes to analyse.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    List.of());
        }

        JavaClasses classes;
        try {
            classes = importer.importPackages(basePackages);
            // Catch LinkageError (e.g. NoClassDefFoundError/ClassFormatError) as well as RuntimeException so a
            // malformed or unresolvable class on the host classpath degrades to a stable report instead of failing.
            // VirtualMachineError (OutOfMemoryError, StackOverflowError) is deliberately not caught here.
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "SCANNED",
                    "Application classes could not be imported for analysis: " + ex.getMessage(),
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    List.of());
        }

        if (classes.isEmpty()) {
            return report(
                    "SCANNED",
                    "No application classes were found under the detected base package(s) to analyse.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    List.of());
        }

        ArchitectureContext context = new ArchitectureContext(classes, basePackages);
        List<ArchitectureRuleResultDto> results = ArchitectureRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();

        return report(
                "SCANNED",
                "Architecture rules completed against " + classes.size()
                        + " application class(es) under the detected base package(s).",
                clock.millis(),
                basePackages,
                classes.size(),
                results.size(),
                results);
    }

    private List<String> safeBasePackages() {
        try {
            List<String> packages = basePackagesSupplier.get();
            return packages == null ? List.of() : List.copyOf(packages);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private ArchitectureReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int classesAnalyzed,
            int rulesEvaluated,
            List<ArchitectureRuleResultDto> results) {
        int violationsFound =
                (int) results.stream().filter(ArchitectureScanner::isViolation).count();
        ArchitectureScanStatusDto scan = new ArchitectureScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, classesAnalyzed, violationsFound);
        return new ArchitectureReport(
                true,
                DISCLAIMER,
                basePackages,
                classesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(results),
                scan,
                results);
    }

    private List<ArchitectureSeverityCountDto> severityCounts(List<ArchitectureRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (ArchitectureRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new ArchitectureSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean isViolation(ArchitectureRuleResultDto result) {
        return ArchitectureRuleSupport.VIOLATION.equals(result.status());
    }
}
