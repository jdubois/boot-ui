package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import io.github.jdubois.bootui.engine.vulnerabilities.VulnerabilityScanner;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
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
 */
@RestController
@RequestMapping("/bootui/api/vulnerabilities")
public class VulnerabilitiesController {

    private final BootUiProperties properties;

    private final DependencyProvider dependencyProvider;

    private final VulnerabilityScanner vulnerabilityScanner;

    private final DismissedRulesStore dismissedRules;

    private volatile DependenciesReport lastScanReport;

    @Autowired
    public VulnerabilitiesController(BootUiProperties properties, DismissedRulesStore dismissedRules) {
        this(
                properties,
                new DependencyCatalog(),
                new OsvVulnerabilityScanner(properties.getVulnerabilities()),
                dismissedRules);
    }

    VulnerabilitiesController(
            BootUiProperties properties,
            DependencyProvider dependencyProvider,
            VulnerabilityScanner vulnerabilityScanner,
            DismissedRulesStore dismissedRules) {
        this.properties = properties;
        this.dependencyProvider = dependencyProvider;
        this.vulnerabilityScanner = vulnerabilityScanner;
        this.dismissedRules = dismissedRules;
    }

    @GetMapping
    public DependenciesReport dependencies() {
        DependenciesReport cached = this.lastScanReport;
        if (cached != null) {
            return DependencyReports.applyDismissals(cached, dismissedRules.load());
        }
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report = DependencyReports.report(
                properties.getVulnerabilities().isOsvEnabled(),
                "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null,
                0,
                dependencies);
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }

    @PostMapping("/scan")
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report;
        if (!properties.getVulnerabilities().isOsvEnabled()) {
            report = DependencyReports.report(
                    false,
                    "DISABLED",
                    "OSV scanning is disabled. Set bootui.vulnerabilities.osv-enabled=true to allow on-demand scans.",
                    null,
                    0,
                    dependencies);
        } else {
            report = vulnerabilityScanner.scan(dependencies);
        }
        if (!"DISABLED".equals(report.status())) {
            this.lastScanReport = report;
        }
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }
}
