package io.github.jdubois.bootui.autoconfigure.architecture;

import io.github.jdubois.bootui.core.BootUiDtos.ArchitectureReport;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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

    private volatile ArchitectureReport lastReport;

    @Autowired
    public ArchitectureController(ApplicationContext applicationContext) {
        this(new ArchitectureScanner(
                () -> ArchitecturePackages.detect(applicationContext),
                new ClassFileArchitectureImporter(),
                Clock.systemUTC()));
    }

    ArchitectureController(ArchitectureScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public ArchitectureReport architecture() {
        return lastReport;
    }

    @PostMapping("/scan")
    public ArchitectureReport scan() {
        ArchitectureReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
