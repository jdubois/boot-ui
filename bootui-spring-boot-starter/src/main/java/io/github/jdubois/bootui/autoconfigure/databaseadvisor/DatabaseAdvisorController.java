package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import io.github.jdubois.bootui.autoconfigure.web.AdvisorViolationsEndpoint;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
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
 * {@link DatabaseAdvisorScanner}, which owns the retained snapshot; this controller applies the
 * adapter's dismissed-rule ids on read.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/database-advisor")
public class DatabaseAdvisorController implements AdvisorViolationsEndpoint {

    private final DatabaseAdvisorScanner scanner;

    private final DismissedRulesStore dismissedRules;

    public DatabaseAdvisorController(DatabaseAdvisorScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GetMapping
    public DatabaseAdvisorReport databaseAdvisor() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    @PostMapping("/scan")
    public DatabaseAdvisorReport scan() {
        DatabaseAdvisorReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
