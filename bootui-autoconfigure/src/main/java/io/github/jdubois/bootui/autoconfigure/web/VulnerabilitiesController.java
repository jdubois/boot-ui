package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/vulnerabilities")
public class VulnerabilitiesController {

    private final BootUiProperties properties;

    private final DependencyProvider dependencyProvider;

    private final VulnerabilityScanner vulnerabilityScanner;

    private volatile DependenciesReport lastScanReport;

    @Autowired
    public VulnerabilitiesController(BootUiProperties properties) {
        this(properties, new DependencyCatalog(), new OsvVulnerabilityScanner(properties.getVulnerabilities()));
    }

    VulnerabilitiesController(
            BootUiProperties properties,
            DependencyProvider dependencyProvider,
            VulnerabilityScanner vulnerabilityScanner) {
        this.properties = properties;
        this.dependencyProvider = dependencyProvider;
        this.vulnerabilityScanner = vulnerabilityScanner;
    }

    @GetMapping
    public DependenciesReport dependencies() {
        DependenciesReport cached = this.lastScanReport;
        if (cached != null) {
            return cached;
        }
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        return OsvVulnerabilityScanner.report(
                properties.getVulnerabilities().isOsvEnabled(),
                "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null,
                0,
                dependencies);
    }

    @PostMapping("/scan")
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report;
        if (!properties.getVulnerabilities().isOsvEnabled()) {
            report = OsvVulnerabilityScanner.report(
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
        return report;
    }
}
