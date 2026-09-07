package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.core.dto.CracScanStatusDto;
import io.github.jdubois.bootui.core.dto.CracSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
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
            "Heuristic checks use the host application's own bytecode and observed Spring resource metadata. "
                    + "They identify manual review needs, not verified active resources, and do not replace an actual "
                    + "checkpoint/restore run on a CRaC-enabled JDK.";

    private static final Comparator<CracFindingDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (CracFindingDto finding) -> SeverityOrder.rank(finding.severity()))
            .thenComparing(
                    Comparator.comparingInt(CracFindingDto::occurrenceCount).reversed())
            .thenComparing(CracFindingDto::id);

    private final Supplier<List<String>> basePackagesSupplier;
    private final CracClassImporter importer;
    private final Clock clock;
    private final Supplier<CracRuntimeInventory> inventorySupplier;
    private final SingleFlightAction singleFlight = new SingleFlightAction();
    private volatile CracRuntimeInventory latestRuntimeInventory =
            CracRuntimeInventory.unavailable("Run readiness checks to collect resource lifecycle evidence.");

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
        return singleFlight.run(ActionOperations.CRAC_SCAN, this::doScan);
    }

    public CracRuntimeInventory latestRuntimeInventory() {
        return latestRuntimeInventory;
    }

    private CracScanResult doScan() {
        BasePackageDetection basePackages = detectBasePackages();
        List<String> warnings = new ArrayList<>(basePackages.warnings());
        JavaClasses classes = null;
        boolean importFailed = false;
        if (basePackages.packages().isEmpty()) {
            warnings.add("No application base package was detected; bytecode-backed checks were skipped.");
        } else {
            try {
                classes = importer.importPackages(basePackages.packages());
                if (classes == null || classes.isEmpty()) {
                    warnings.add("No application classes were found; bytecode-backed checks were skipped.");
                }
            } catch (RuntimeException | LinkageError ex) {
                importFailed = true;
                warnings.add("Application classes could not be imported for analysis ("
                        + ex.getClass().getSimpleName() + "); bytecode-backed checks were skipped.");
            }
        }

        InventorySnapshot inventory = safeInventory();
        latestRuntimeInventory = inventory.inventory();
        warnings.addAll(inventory.warnings());
        boolean bytecodeAvailable = classes != null && !classes.isEmpty();
        CracContext context = new CracContext(classes, basePackages.packages(), inventory.inventory());
        List<CracFindingDto> results = new ArrayList<>();
        int checksRun = 0;
        for (CracCheck check : CracCheckRegistry.activeChecks()) {
            if (check.evidence() != CracCheck.Evidence.RUNTIME && !bytecodeAvailable) {
                results.add(CracCheckSupport.skipped(check.definition(), "Application bytecode is unavailable."));
            } else if (check.evidence() != CracCheck.Evidence.BYTECODE
                    && !inventory.inventory().available()) {
                results.add(CracCheckSupport.skipped(check.definition(), "Runtime evidence is unavailable."));
            } else {
                results.add(check.evaluate(context));
                checksRun++;
            }
        }

        return new CracScanResult(
                basePackages.failed() || importFailed || !inventory.inventory().available() ? "ERROR" : "SCANNED",
                "Evaluated " + checksRun + " readiness check(s) against "
                        + (bytecodeAvailable ? classes.size() : 0)
                        + " application class(es) and the available runtime evidence. "
                        + "Skipped observations are not evidence of readiness.",
                clock.millis(),
                basePackages.packages(),
                bytecodeAvailable ? classes.size() : 0,
                checksRun,
                results,
                warnings);
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

    private InventorySnapshot safeInventory() {
        try {
            CracRuntimeInventory inventory = inventorySupplier.get();
            if (inventory == null) {
                inventory = CracRuntimeInventory.unavailable(
                        "CRaC runtime inventory collection returned no data; runtime-backed checks were skipped.");
            }
            return new InventorySnapshot(inventory, inventory.warnings());
        } catch (RuntimeException | LinkageError ex) {
            CracRuntimeInventory inventory =
                    CracRuntimeInventory.unavailable("CRaC runtime inventory could not be collected ("
                            + ex.getClass().getSimpleName() + "); runtime-backed checks were skipped.");
            return new InventorySnapshot(inventory, inventory.warnings());
        }
    }

    private BasePackageDetection detectBasePackages() {
        try {
            List<String> packages = basePackagesSupplier.get();
            return new BasePackageDetection(packages == null ? List.of() : List.copyOf(packages), List.of(), false);
        } catch (RuntimeException | LinkageError ex) {
            return new BasePackageDetection(
                    List.of(),
                    List.of("Application base packages could not be detected ("
                            + ex.getClass().getSimpleName() + ")."),
                    true);
        }
    }

    private List<CracSeverityCountDto> severityCounts(List<CracFindingDto> findings) {
        Map<String, Integer> counts = SeverityOrder.counts(findings, CracFindingDto::severity);
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

    private record BasePackageDetection(List<String> packages, List<String> warnings, boolean failed) {}

    private record InventorySnapshot(CracRuntimeInventory inventory, List<String> warnings) {}
}
