package io.github.jdubois.bootui.autoconfigure.architecture;

import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Architecture (ArchUnit) hygiene panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} runs the
 * curated ArchUnit ruleset against the host application classes and caches the result.</p>
 */
@RestController
@RequestMapping("/bootui/api/architecture")
public class ArchitectureController {

    private final ArchitectureScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile ArchitectureReport lastReport;

    public ArchitectureController(ArchitectureScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public ArchitectureReport architecture() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @PostMapping("/scan")
    public ArchitectureReport scan() {
        ArchitectureReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
