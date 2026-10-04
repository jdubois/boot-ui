package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.VulnerabilityReach;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import io.github.jdubois.bootui.engine.vulnerabilities.VulnerabilityScanner;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Vulnerabilities panel.
 *
 * <p>{@code GET} returns the last scan (initially the local dependency inventory, unscanned);
 * {@code POST /scan} queries OSV.dev. Per-vulnerability dismissals (a developer acknowledging a finding they
 * can't immediately fix) are stored in the shared {@link DismissedRulesStore} keyed by
 * {@link DependencyReports#dismissalKey(String, String)} and applied to whichever report is returned,
 * mirroring every other advisor's dismiss/restore wiring.</p>
 *
 * <p>Both answers carry each dependency's runtime reach from Code Inventory, read when answered, never cached with the
 * scan ({@link VulnerabilityReach}); it never changes a finding's severity or any count.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/vulnerabilities")
public class VulnerabilitiesController {

    private final BootUiProperties properties;

    private final DependencyProvider dependencyProvider;

    private final VulnerabilityScanner vulnerabilityScanner;

    private final DismissedRulesStore dismissedRules;
    private final Supplier<CodeInventoryService> codeInventory;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    private volatile DependenciesReport lastScanReport;

    @Autowired
    public VulnerabilitiesController(
            BootUiProperties properties,
            DismissedRulesStore dismissedRules,
            ObjectProvider<BasePackageProvider> basePackageProvider,
            ObjectProvider<CodeInventoryService> codeInventory) {
        this(
                properties,
                new DependencyCatalog(new PathMatchingResourcePatternResolver(), () -> {
                    BasePackageProvider provider = basePackageProvider.getIfAvailable();
                    return provider == null ? List.of() : provider.basePackages();
                }),
                new OsvVulnerabilityScanner(properties.getVulnerabilities()),
                dismissedRules,
                codeInventory::getIfUnique);
    }

    VulnerabilitiesController(
            BootUiProperties properties,
            DependencyProvider dependencyProvider,
            VulnerabilityScanner vulnerabilityScanner,
            DismissedRulesStore dismissedRules) {
        this(properties, dependencyProvider, vulnerabilityScanner, dismissedRules, () -> null);
    }

    VulnerabilitiesController(
            BootUiProperties properties,
            DependencyProvider dependencyProvider,
            VulnerabilityScanner vulnerabilityScanner,
            DismissedRulesStore dismissedRules,
            Supplier<CodeInventoryService> codeInventory) {
        this.properties = properties;
        this.dependencyProvider = dependencyProvider;
        this.vulnerabilityScanner = vulnerabilityScanner;
        this.dismissedRules = dismissedRules;
        this.codeInventory = codeInventory;
    }

    /** The report as answered: dismissals marked, then runtime reach read now. */
    private DependenciesReport answer(DependenciesReport report) {
        return VulnerabilityReach.annotate(
                DependencyReports.applyDismissals(report, dismissedRules.load()),
                codeInventory,
                () -> properties.isPanelEnabled(BootUiPanels.CODE_INVENTORY));
    }

    @GetMapping
    public DependenciesReport dependencies() {
        DependenciesReport cached = this.lastScanReport;
        if (cached != null) {
            return answer(cached);
        }
        DependencyInventory inventory = dependencyProvider.inventory();
        DependenciesReport report = DependencyReports.report(
                properties.getVulnerabilities().isOsvEnabled(),
                "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null,
                0,
                0,
                inventory.dependencies(),
                inventory.coverage());
        return answer(report);
    }

    @PostMapping("/scan")
    public DependenciesReport scan() {
        DependencyInventory inventory = dependencyProvider.inventory();
        DependenciesReport report;
        if (!properties.getVulnerabilities().isOsvEnabled()) {
            report = DependencyReports.report(
                    false,
                    "DISABLED",
                    "OSV scanning is disabled. Set bootui.vulnerabilities.osv-enabled=true to allow on-demand scans.",
                    null,
                    0,
                    0,
                    inventory.dependencies(),
                    inventory.coverage());
        } else {
            report =
                    singleFlight.run(ActionOperations.VULNERABILITIES_SCAN, () -> vulnerabilityScanner.scan(inventory));
        }
        if (!"DISABLED".equals(report.status())) {
            this.lastScanReport = report;
        }
        return answer(report);
    }
}
