package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.DependenciesReport;
import io.github.jdubois.bootui.core.BootUiDtos.DependencyDto;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/dependencies")
public class DependenciesController {

    private final BootUiProperties properties;

    private final DependencyProvider dependencyProvider;

    private final VulnerabilityScanner vulnerabilityScanner;

    private volatile DependenciesReport lastScanReport;

    @Autowired
    public DependenciesController(BootUiProperties properties) {
        this(properties, new DependencyCatalog(), new OsvVulnerabilityScanner(properties.getDependencies()));
    }

    DependenciesController(BootUiProperties properties, DependencyProvider dependencyProvider,
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
        return OsvVulnerabilityScanner.report(properties.getDependencies().isOsvEnabled(), "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null, 0, dependencies);
    }

    @PostMapping("/scan")
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report;
        if (!properties.getDependencies().isOsvEnabled()) {
            report = OsvVulnerabilityScanner.report(false, "DISABLED",
                    "OSV scanning is disabled. Set bootui.dependencies.osv-enabled=true to allow on-demand scans.",
                    null, 0, dependencies);
        }
        else {
            report = vulnerabilityScanner.scan(dependencies);
        }
        this.lastScanReport = report;
        return report;
    }
}
