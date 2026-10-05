package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.autoconfigure.web.AdvisorViolationsEndpoint;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Hibernate Advisor panel.
 *
 * <p>{@code GET} returns the last advisor report (initially "not scanned"); {@code POST /scan} reads the
 * Hibernate/JPA metamodel and evaluates a bounded, static ruleset against mapped application
 * entities. The scan logic and retained snapshot live in the engine {@link HibernateScanner};
 * this controller applies the adapter's dismissed-rule ids on read.</p>
 *
 * <p>The Hibernate Statistics panel (live {@code SessionFactory} statistics) is a separate
 * Database-group panel served by {@link HibernateStatisticsController}, not this advisor.</p>
 */
@RestController
@ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/hibernate")
public class HibernateController implements AdvisorViolationsEndpoint {

    private final HibernateScanner scanner;

    private final DismissedRulesStore dismissedRules;

    public HibernateController(HibernateScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
    }

    @GetMapping
    public HibernateReport hibernate() {
        return scanner.applyDismissals(scanner.lastReport(), dismissedRules.load());
    }

    @PostMapping("/scan")
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @Override
    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return scanner.ruleViolations(ruleId, scanId, offset, limit);
    }
}
