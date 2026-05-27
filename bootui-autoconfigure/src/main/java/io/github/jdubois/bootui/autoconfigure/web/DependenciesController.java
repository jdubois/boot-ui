package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.DependenciesReport;
import io.github.jdubois.bootui.core.BootUiDtos.DependencyDto;
import io.github.jdubois.bootui.core.BootUiDtos.DependencyGroupDto;
import io.github.jdubois.bootui.core.BootUiDtos.DependencyTreeReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        return OsvVulnerabilityScanner.report(properties.getDependencies().isOsvEnabled(), "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null, 0, dependencies);
    }

    @PostMapping("/scan")
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        if (!properties.getDependencies().isOsvEnabled()) {
            return OsvVulnerabilityScanner.report(false, "DISABLED",
                    "OSV scanning is disabled. Set bootui.dependencies.osv-enabled=true to allow on-demand scans.",
                    null, 0, dependencies);
        }
        return vulnerabilityScanner.scan(dependencies);
    }

    @GetMapping("/tree")
    public DependencyTreeReport tree() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        Map<String, List<DependencyDto>> byGroup = new TreeMap<>();
        for (DependencyDto dep : dependencies) {
            byGroup.computeIfAbsent(dep.groupId(), k -> new ArrayList<>()).add(dep);
        }

        List<DependencyGroupDto> groups = byGroup.entrySet().stream()
                .map(entry -> {
                    List<DependencyDto> artifacts = entry.getValue().stream()
                            .sorted(Comparator.comparing(DependencyDto::artifactId))
                            .toList();
                    int vulnerableCount = (int) artifacts.stream().filter(d -> d.vulnerabilityCount() > 0).count();
                    return new DependencyGroupDto(entry.getKey(), artifacts.size(), vulnerableCount, artifacts);
                })
                .toList();

        int totalVulnerable = groups.stream().mapToInt(DependencyGroupDto::vulnerableCount).sum();
        return new DependencyTreeReport(groups.size(), dependencies.size(), totalVulnerable, groups);
    }
}
