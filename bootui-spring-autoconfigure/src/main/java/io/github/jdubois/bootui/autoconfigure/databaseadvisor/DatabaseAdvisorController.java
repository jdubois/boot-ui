package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Database Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} introspects the
 * physical schema of every discovered {@code DataSource} through plain JDBC {@code DatabaseMetaData} and
 * evaluates a bounded, static ruleset (schema-only checks plus, when a Hibernate metamodel is also
 * available, cross-reference checks). The scan logic lives in the engine
 * {@link DatabaseAdvisorScanner}; this controller only caches the last report and applies the adapter's
 * dismissed-rule ids.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/database-advisor")
public class DatabaseAdvisorController {

    private final DatabaseAdvisorScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile DatabaseAdvisorReport lastReport;

    public DatabaseAdvisorController(DatabaseAdvisorScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public DatabaseAdvisorReport databaseAdvisor() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @PostMapping("/scan")
    public DatabaseAdvisorReport scan() {
        DatabaseAdvisorReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
