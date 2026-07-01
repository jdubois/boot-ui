package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.core.dto.CracScanStatusDto;
import io.github.jdubois.bootui.core.dto.CracSeverityCountDto;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded, on-demand CRaC (Coordinated Restore at Checkpoint) readiness scanner.
 *
 * <p>The scanner imports only the host application's own classes (bounded to the detected
 * {@code @SpringBootApplication} base packages) and runs a fixed registry of curated readiness
 * checks. Results are heuristic review prompts that complement, but do not replace, an actual
 * checkpoint/restore run on a CRaC-enabled JDK.</p>
 */
public final class CracReadinessScanner {

    static final String ANALYZER = "BootUI CRaC readiness";
    static final String DISCLAIMER =
            "Heuristic static checks run against the host application's own classes only. They highlight code that "
                    + "commonly needs attention before a checkpoint, but they complement, and do not replace, an actual "
                    + "checkpoint/restore run on a CRaC-enabled JDK.";

    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<CracFindingDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (CracFindingDto finding) -> severityRank(finding.severity()))
            .thenComparing(
                    Comparator.comparingInt(CracFindingDto::occurrenceCount).reversed())
            .thenComparing(CracFindingDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final CracClassImporter importer;
    private final Clock clock;
    private final Supplier<CracRuntimeInventory> inventorySupplier;

    CracReadinessScanner(Supplier<List<String>> basePackagesSupplier, CracClassImporter importer, Clock clock) {
        this(basePackagesSupplier, importer, clock, CracRuntimeInventory::empty);
    }

    CracReadinessScanner(
            Supplier<List<String>> basePackagesSupplier,
            CracClassImporter importer,
            Clock clock,
            Supplier<CracRuntimeInventory> inventorySupplier) {
        this.basePackagesSupplier = basePackagesSupplier;
        this.importer = importer;
        this.clock = clock;
        this.inventorySupplier = inventorySupplier;
    }

    /**
     * Builds a scanner that imports the host application's compiled classes from the classpath, bounded to
     * the supplied base packages, and reads a live runtime inventory through the supplied seam. Base packages
     * are read <em>live</em> on every scan (the supplier is typically backed by a {@code BasePackageProvider}
     * SPI), and the runtime inventory is captured once per scan; the ArchUnit import runs only on demand
     * (POST /scan), never at construction.
     */
    public static CracReadinessScanner usingClasspath(
            Supplier<List<String>> basePackagesSupplier,
            Supplier<CracRuntimeInventory> inventorySupplier,
            Clock clock) {
        return new CracReadinessScanner(basePackagesSupplier, new ClassFileCracImporter(), clock, inventorySupplier);
    }

    public CracScanResult initialResult() {
        BasePackageDetection basePackages = detectBasePackages();
        return new CracScanResult(
                "NOT_SCANNED",
                "Readiness checks have not run yet. Click Run readiness checks to analyse the application.",
                null,
                basePackages.packages(),
                0,
                0,
                List.of(),
                basePackages.warnings());
    }

    public CracScanResult scan() {
        BasePackageDetection basePackages = detectBasePackages();
        if (basePackages.packages().isEmpty()) {
            return new CracScanResult(
                    "SCANNED",
                    "No application base package was detected, so there were no classes to analyse.",
                    clock.millis(),
                    basePackages.packages(),
                    0,
                    0,
                    List.of(),
                    basePackages.warnings());
        }

        JavaClasses classes;
        try {
            classes = importer.importPackages(basePackages.packages());
            // Catch LinkageError (e.g. NoClassDefFoundError/ClassFormatError) as well as RuntimeException so a
            // malformed or unresolvable class on the host classpath degrades to a stable report instead of failing.
        } catch (RuntimeException | LinkageError ex) {
            String warning = "Application classes could not be imported for analysis: "
                    + CracCheckSupport.detail(ex.getMessage());
            return new CracScanResult(
                    "ERROR",
                    warning,
                    clock.millis(),
                    basePackages.packages(),
                    0,
                    0,
                    List.of(),
                    concat(basePackages.warnings(), warning));
        }

        if (classes.isEmpty()) {
            return new CracScanResult(
                    "SCANNED",
                    "No application classes were found under the detected base package(s) to analyse.",
                    clock.millis(),
                    basePackages.packages(),
                    0,
                    0,
                    List.of(),
                    basePackages.warnings());
        }

        CracContext context = new CracContext(classes, basePackages.packages(), safeInventory());
        List<CracFindingDto> results = CracCheckRegistry.activeChecks().stream()
                .map(check -> check.evaluate(context))
                .toList();

        return new CracScanResult(
                "SCANNED",
                "Readiness checks completed against " + classes.size()
                        + " application class(es) under the detected base package(s).",
                clock.millis(),
                basePackages.packages(),
                classes.size(),
                results.size(),
                results,
                basePackages.warnings());
    }

    /** Assembles the DTO report served to the panel from a cached scan plus a fresh runtime status. */
    public CracReadinessReport report(CracScanResult scan, CracRuntimeStatusDto runtime) {
        List<CracFindingDto> findings = reviewFindings(scan.findings());
        int findingsFound = findings.size();
        CracScanStatusDto status = new CracScanStatusDto(
                ANALYZER,
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.checksRun(),
                scan.classesAnalyzed(),
                findingsFound);
        return new CracReadinessReport(
                true,
                DISCLAIMER,
                runtime,
                scan.basePackages(),
                scan.classesAnalyzed(),
                scan.checksRun(),
                findingsFound,
                severityCounts(findings),
                status,
                findings,
                scan.warnings(),
                List.of());
    }

    private CracRuntimeInventory safeInventory() {
        try {
            CracRuntimeInventory inventory = inventorySupplier.get();
            return inventory == null ? CracRuntimeInventory.empty() : inventory;
        } catch (RuntimeException ex) {
            return CracRuntimeInventory.empty();
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
                            + CracCheckSupport.detail(ex.getMessage())));
        }
    }

    private static List<String> concat(List<String> warnings, String extra) {
        return java.util.stream.Stream.concat(warnings.stream(), java.util.stream.Stream.of(extra))
                .toList();
    }

    private List<CracSeverityCountDto> severityCounts(List<CracFindingDto> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (CracFindingDto finding : findings) {
            counts.computeIfPresent(finding.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new CracSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<CracFindingDto> reviewFindings(List<CracFindingDto> results) {
        return results.stream()
                .filter(result -> CracCheckSupport.REVIEW.equals(result.status())
                        || CracCheckSupport.ERROR.equals(result.status()))
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    /** Scan-specific portion of a CRaC readiness run, cached by the controller between requests. */
    public record CracScanResult(
            String status,
            String message,
            Long scannedAt,
            List<String> basePackages,
            int classesAnalyzed,
            int checksRun,
            List<CracFindingDto> findings,
            List<String> warnings) {

        public CracScanResult {
            basePackages = List.copyOf(basePackages);
            findings = List.copyOf(findings);
            warnings = List.copyOf(warnings);
        }
    }

    private record BasePackageDetection(List<String> packages, List<String> warnings) {}
}
