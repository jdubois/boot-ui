package io.github.jdubois.bootui.autoconfigure.graalvm;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import io.github.jdubois.bootui.core.dto.GraalVmMetadataSummaryDto;
import io.github.jdubois.bootui.core.dto.GraalVmReadinessReport;
import io.github.jdubois.bootui.core.dto.GraalVmScanStatusDto;
import io.github.jdubois.bootui.core.dto.GraalVmSeverityCountDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded, on-demand GraalVM native-image readiness scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages) and runs a fixed registry of curated readiness
 * checks plus an optional classpath dependency-metadata survey. Results are heuristic review prompts
 * that complement, but do not replace, the GraalVM tracing agent and an actual native build.</p>
 */
final class GraalVmReadinessScanner {

    static final String ANALYZER = "BootUI GraalVM readiness";
    static final String DISCLAIMER =
            "Heuristic static checks run against the host application's own classes only, plus a survey of which "
                    + "dependencies ship reachability metadata. They complement, but do not replace, the GraalVM "
                    + "tracing agent and an actual native-image build.";

    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<GraalVmFindingDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (GraalVmFindingDto finding) -> severityRank(finding.severity()))
            .thenComparing(
                    Comparator.comparingInt(GraalVmFindingDto::occurrenceCount).reversed())
            .thenComparing(GraalVmFindingDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final GraalVmClassImporter importer;
    private final GraalVmDependencyScanner dependencyScanner;
    private final Clock clock;

    GraalVmReadinessScanner(
            Supplier<List<String>> basePackagesSupplier,
            GraalVmClassImporter importer,
            GraalVmDependencyScanner dependencyScanner,
            Clock clock) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.dependencyScanner = dependencyScanner;
        this.clock = clock;
    }

    GraalVmScanResult initialResult() {
        BasePackageDetection basePackages = detectBasePackages();
        GraalVmReadinessReport report = report(
                "NOT_SCANNED",
                "Readiness checks have not run yet. Click Run readiness checks to analyse the application.",
                null,
                basePackages.packages(),
                true,
                0,
                0,
                List.of(),
                List.of(),
                basePackages.warnings(),
                GraalVmMetadata.empty());
        return new GraalVmScanResult(report, GraalVmMetadata.empty());
    }

    GraalVmScanResult scan(boolean includeDependencies) {
        BasePackageDetection basePackages = detectBasePackages();
        DependencyScan dependencies = dependencies(includeDependencies);
        if (basePackages.packages().isEmpty()) {
            GraalVmReadinessReport report = report(
                    "SCANNED",
                    "No application base package was detected, so there were no classes to analyse.",
                    clock.millis(),
                    basePackages.packages(),
                    includeDependencies,
                    0,
                    0,
                    List.of(),
                    dependencies.dependencies(),
                    warnings(basePackages, dependencies),
                    GraalVmMetadata.empty());
            return new GraalVmScanResult(report, GraalVmMetadata.empty());
        }

        JavaClasses classes;
        try {
            classes = importer.importPackages(basePackages.packages());
            // Catch LinkageError (e.g. NoClassDefFoundError/ClassFormatError) as well as RuntimeException so a
            // malformed or unresolvable class on the host classpath degrades to a stable report instead of failing.
        } catch (RuntimeException | LinkageError ex) {
            String warning = "Application classes could not be imported for analysis: "
                    + GraalVmCheckSupport.detail(ex.getMessage());
            GraalVmReadinessReport report = report(
                    "ERROR",
                    warning,
                    clock.millis(),
                    basePackages.packages(),
                    includeDependencies,
                    0,
                    0,
                    List.of(),
                    dependencies.dependencies(),
                    warnings(basePackages, dependencies, warning),
                    GraalVmMetadata.empty());
            return new GraalVmScanResult(report, GraalVmMetadata.empty());
        }

        if (classes.isEmpty()) {
            GraalVmReadinessReport report = report(
                    "SCANNED",
                    "No application classes were found under the detected base package(s) to analyse.",
                    clock.millis(),
                    basePackages.packages(),
                    includeDependencies,
                    0,
                    0,
                    List.of(),
                    dependencies.dependencies(),
                    warnings(basePackages, dependencies),
                    GraalVmMetadata.empty());
            return new GraalVmScanResult(report, GraalVmMetadata.empty());
        }

        GraalVmContext context = new GraalVmContext(classes, basePackages.packages());
        List<GraalVmFindingDto> results = GraalVmCheckRegistry.activeChecks().stream()
                .map(check -> check.evaluate(context))
                .toList();

        GraalVmMetadata metadata = new GraalVmMetadata(
                GraalVmClassPredicates.reflectionCandidateTypeNames(classes),
                GraalVmClassPredicates.serializationCandidateTypeNames(classes),
                GraalVmMetadata.DEFAULT_RESOURCE_GLOBS);

        GraalVmReadinessReport report = report(
                "SCANNED",
                "Readiness checks completed against " + classes.size()
                        + " application class(es) under the detected base package(s).",
                clock.millis(),
                basePackages.packages(),
                includeDependencies,
                classes.size(),
                results.size(),
                results,
                dependencies.dependencies(),
                warnings(basePackages, dependencies),
                metadata);
        return new GraalVmScanResult(report, metadata);
    }

    private DependencyScan dependencies(boolean includeDependencies) {
        if (!includeDependencies) {
            return new DependencyScan(List.of(), List.of());
        }
        try {
            return new DependencyScan(dependencyScanner.scan(), List.of());
        } catch (RuntimeException ex) {
            return new DependencyScan(
                    List.of(),
                    List.of("Dependency metadata survey could not be completed: "
                            + GraalVmCheckSupport.detail(ex.getMessage())));
        }
    }

    private BasePackageDetection detectBasePackages() {
        try {
            List<String> packages = basePackagesSupplier.get();
            return new BasePackageDetection(packages == null ? List.of() : List.copyOf(packages), List.of());
        } catch (RuntimeException ex) {
            return new BasePackageDetection(
                    List.of(),
                    List.of("Application base packages could not be detected: "
                            + GraalVmCheckSupport.detail(ex.getMessage())));
        }
    }

    private List<String> warnings(BasePackageDetection basePackages, DependencyScan dependencies) {
        return java.util.stream.Stream.concat(basePackages.warnings().stream(), dependencies.warnings().stream())
                .toList();
    }

    private List<String> warnings(BasePackageDetection basePackages, DependencyScan dependencies, String warning) {
        return java.util.stream.Stream.concat(
                        warnings(basePackages, dependencies).stream(), java.util.stream.Stream.of(warning))
                .toList();
    }

    private GraalVmReadinessReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            boolean includeDependencies,
            int classesAnalyzed,
            int checksRun,
            List<GraalVmFindingDto> results,
            List<GraalVmDependencyDto> dependencies,
            List<String> warnings,
            GraalVmMetadata metadata) {
        List<GraalVmFindingDto> findings = reviewFindings(results);
        int findingsFound = findings.size();
        GraalVmScanStatusDto scan = new GraalVmScanStatusDto(
                ANALYZER, status, message, scannedAt, checksRun, classesAnalyzed, findingsFound);
        int dependenciesWithoutMetadata =
                (int) dependencies.stream().filter(dep -> !dep.shipsMetadata()).count();
        GraalVmMetadataSummaryDto metadataSummary = new GraalVmMetadataSummaryDto(
                metadata.reflectionTypes().size(),
                metadata.serializationTypes().size(),
                metadata.resourceGlobs().size());
        return new GraalVmReadinessReport(
                true,
                DISCLAIMER,
                basePackages,
                includeDependencies,
                classesAnalyzed,
                checksRun,
                findingsFound,
                severityCounts(findings),
                scan,
                findings,
                dependencies.size(),
                dependenciesWithoutMetadata,
                dependencies,
                warnings,
                metadataSummary);
    }

    private List<GraalVmSeverityCountDto> severityCounts(List<GraalVmFindingDto> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (GraalVmFindingDto finding : findings) {
            counts.computeIfPresent(finding.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new GraalVmSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<GraalVmFindingDto> reviewFindings(List<GraalVmFindingDto> results) {
        return results.stream()
                .filter(result -> GraalVmCheckSupport.REVIEW.equals(result.status())
                        || GraalVmCheckSupport.ERROR.equals(result.status()))
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    /** Result of one scan: the report served to the panel plus the metadata scaffold candidates. */
    record GraalVmScanResult(GraalVmReadinessReport report, GraalVmMetadata metadata) {}

    private record BasePackageDetection(List<String> packages, List<String> warnings) {}

    private record DependencyScan(List<GraalVmDependencyDto> dependencies, List<String> warnings) {}
}
