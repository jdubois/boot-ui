package io.github.jdubois.bootui.autoconfigure.hibernate;

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
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} reads the
 * Hibernate/JPA metamodel and evaluates a bounded, static ruleset against mapped application
 * entities. The scan logic lives in the engine {@link HibernateScanner}; this controller only caches
 * the last report and applies the adapter's dismissed-rule ids.</p>
 */
@RestController
@ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
@RequestMapping("/bootui/api/hibernate")
public class HibernateController {

    private final HibernateScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private volatile HibernateReport lastReport;

    public HibernateController(HibernateScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public HibernateReport hibernate() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @PostMapping("/scan")
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
