package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.core.dto.ArchitectureScanStatusDto;
import io.github.jdubois.bootui.core.dto.ArchitectureSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand ArchUnit hygiene scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages) and runs a fixed registry of curated, project-
 * agnostic architecture rules. Results are heuristic review prompts, not architectural verdicts.</p>
 */
public final class ArchitectureScanner {

    private static final String ANALYZER = "BootUI ArchUnit hygiene";
    private static final String DISCLAIMER =
            "Heuristic, project-agnostic architecture rules run against the host application's own classes only. "
                    + "These checks complement, but do not replace, a project-specific ArchUnit test suite or an "
                    + "architecture review.";
    private static final Comparator<ArchitectureRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (ArchitectureRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(ArchitectureRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(ArchitectureRuleResultDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final ArchitectureClassImporter importer;
    private final ArchitecturePlatform platform;
    private final Clock clock;
    private final List<ArchitectureRule> rules;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    ArchitectureScanner(
            Supplier<List<String>> basePackagesSupplier,
            ArchitectureClassImporter importer,
            ArchitecturePlatform platform,
            Clock clock) {
        this(basePackagesSupplier, importer, platform, clock, ArchitectureRuleRegistry.activeRules());
    }

    ArchitectureScanner(
            Supplier<List<String>> basePackagesSupplier,
            ArchitectureClassImporter importer,
            ArchitecturePlatform platform,
            Clock clock,
            List<ArchitectureRule> rules) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.platform = platform;
        this.clock = clock;
        this.rules = List.copyOf(rules);
    }

    /**
     * Builds a scanner that imports the host application's compiled classes from the classpath, bounded
     * to the supplied base packages. This is the entry point adapters wire: the base packages are read
     * <em>live</em> on every scan (the supplier is typically backed by a {@code BasePackageProvider} SPI),
     * and the ArchUnit import runs only on demand, never at construction.
     */
    public static ArchitectureScanner usingClasspath(
            Supplier<List<String>> basePackagesSupplier, ArchitecturePlatform platform, Clock clock) {
        return new ArchitectureScanner(basePackagesSupplier, new ClassFileArchitectureImporter(), platform, clock);
    }

    /**
     * Builds a Spring-platform scanner, preserving the original public factory contract.
     *
     * @see #usingClasspath(Supplier, ArchitecturePlatform, Clock)
     */
    public static ArchitectureScanner usingClasspath(Supplier<List<String>> basePackagesSupplier, Clock clock) {
        return usingClasspath(basePackagesSupplier, ArchitecturePlatform.SPRING, clock);
    }

    public ArchitectureReport initialReport() {
        List<String> basePackages;
        try {
            basePackages = basePackages();
        } catch (RuntimeException | LinkageError ex) {
            return failure("Application base packages could not be detected", ex, null, List.of());
        }
        return report(
                "NOT_SCANNED",
                "Architecture rules have not run yet. Click Run architecture checks to analyse the application"
                        + " classes.",
                null,
                basePackages,
                0,
                0,
                List.of());
    }

    public ArchitectureReport scan() {
        return singleFlight.run(ActionOperations.ARCHITECTURE_SCAN, this::doScan);
    }

    private ArchitectureReport doScan() {
        List<String> basePackages;
        try {
            basePackages = basePackages();
        } catch (RuntimeException | LinkageError ex) {
            return failure("Application base packages could not be detected", ex, clock.millis(), List.of());
        }
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
            classes = Objects.requireNonNull(importer.importPackages(basePackages));
            // Catch LinkageError (e.g. NoClassDefFoundError/ClassFormatError) as well as RuntimeException so a
            // malformed or unresolvable class on the host classpath degrades to a stable report instead of failing.
            // VirtualMachineError (OutOfMemoryError, StackOverflowError) is deliberately not caught here.
        } catch (RuntimeException | LinkageError ex) {
            return failure("Application classes could not be imported for analysis", ex, clock.millis(), basePackages);
        }

        if (classes.isEmpty()) {
            return report(
                    "SCANNED",
                    "No application classes were found under the detected base package(s) to analyse.",
                    clock.millis(),
                    basePackages,
                    0,
                    0,
                    List.of(),
                    new AdvisorEvidenceDto(false, true, List.of()));
        }

        ArchitectureContext context = new ArchitectureContext(classes, basePackages, platform);
        List<ArchitectureRuleResultDto> results = new java.util.ArrayList<>();
        boolean usable = false;
        List<String> unreported = new java.util.ArrayList<>();
        for (ArchitectureRule rule : rules) {
            context.evidence().reset();
            ArchitectureRuleResultDto result = rule.evaluate(context);
            results.add(result);
            if (!context.evidence().evaluated() && !ArchitectureRuleSupport.ERROR.equals(result.status())) {
                unreported.add(result.id() + ": evaluator did not supply observation evidence.");
            }
            if (context.evidence().requiredUnknown()) {
                unreported.add(result.id() + ": required architecture observations could not be resolved.");
            }
            usable |= context.evidence().usable();
        }
        long errors = results.stream()
                .filter(result -> ArchitectureRuleSupport.ERROR.equals(result.status()))
                .count();
        boolean hasSuccessfulEvaluation = results.stream()
                .anyMatch(result -> ArchitectureRuleSupport.PASS.equals(result.status()) || isViolation(result));
        String status = errors == 0 ? "SCANNED" : hasSuccessfulEvaluation ? "PARTIAL" : "ERROR";
        String message = errors == 0
                ? "Architecture rules completed against " + classes.size()
                        + " application class(es) under the detected base package(s)."
                : "Architecture analysis is incomplete: " + errors + " rule(s) could not be evaluated against "
                        + classes.size() + " application class(es).";

        List<String> limitations = java.util.stream.Stream.concat(
                        unreported.stream(),
                        results.stream()
                                .filter(result -> ArchitectureRuleSupport.ERROR.equals(result.status()))
                                .map(result -> result.id() + ": architecture evaluation failed."))
                .limit(20)
                .toList();
        return report(
                status,
                message,
                clock.millis(),
                basePackages,
                classes.size(),
                results.size(),
                results,
                new AdvisorEvidenceDto(usable, errors == 0 && unreported.isEmpty(), limitations));
    }

    private List<String> basePackages() {
        List<String> packages = List.copyOf(basePackagesSupplier.get());
        if (packages.stream().anyMatch(ArchitectureScanner::invalidPackage)) {
            throw new IllegalArgumentException("Invalid application base package.");
        }
        return packages;
    }

    private static boolean invalidPackage(String name) {
        // JVM package segments need not be Java identifiers (for example, escaped Kotlin names).
        for (String segment : name.split("\\.", -1)) {
            if (segment.isBlank()
                    || segment.chars().anyMatch(c -> "/\\;[*".indexOf(c) >= 0 || Character.isISOControl(c))) {
                return true;
            }
        }
        return false;
    }

    private ArchitectureReport failure(String message, Throwable error, Long scannedAt, List<String> basePackages) {
        return report(
                "ERROR",
                ArchitectureRuleSupport.detail(message + " (" + error.getClass().getSimpleName() + ")."),
                scannedAt,
                basePackages,
                0,
                0,
                List.of());
    }

    private ArchitectureReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int classesAnalyzed,
            int rulesEvaluated,
            List<ArchitectureRuleResultDto> results) {
        return report(
                status,
                message,
                scannedAt,
                basePackages,
                classesAnalyzed,
                rulesEvaluated,
                results,
                AdvisorEvidenceDto.unknown());
    }

    private ArchitectureReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int classesAnalyzed,
            int rulesEvaluated,
            List<ArchitectureRuleResultDto> results,
            AdvisorEvidenceDto evidence) {
        List<ArchitectureRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        ArchitectureScanStatusDto scan = new ArchitectureScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, classesAnalyzed, violationsFound);
        return new ArchitectureReport(
                true,
                DISCLAIMER,
                basePackages,
                classesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results),
                evidence);
    }

    public ArchitectureReport applyDismissals(ArchitectureReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<ArchitectureRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<ArchitectureRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        ArchitectureScanStatusDto scan = report.scan();
        ArchitectureScanStatusDto updatedScan = new ArchitectureScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.classesAnalyzed(),
                violationsFound);
        return new ArchitectureReport(
                report.localOnly(),
                report.disclaimer(),
                report.basePackages(),
                report.classesAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked,
                report.analysisErrors(),
                report.evidence());
    }

    static List<ArchitectureRuleResultDto> analysisErrors(List<ArchitectureRuleResultDto> results) {
        return results.stream()
                .filter(result -> ArchitectureRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(ArchitectureRuleResultDto::id))
                .toList();
    }

    private List<ArchitectureSeverityCountDto> severityCounts(List<ArchitectureRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results,
                ArchitectureScanner::isViolation,
                ArchitectureRuleResultDto::severity,
                ArchitectureRuleResultDto::violationCount);
        return counts.entrySet().stream()
                .map(entry -> new ArchitectureSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<ArchitectureRuleResultDto> violationResults(List<ArchitectureRuleResultDto> results) {
        return results.stream()
                .filter(ArchitectureScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static boolean isViolation(ArchitectureRuleResultDto result) {
        return ArchitectureRuleSupport.VIOLATION.equals(result.status());
    }
}
