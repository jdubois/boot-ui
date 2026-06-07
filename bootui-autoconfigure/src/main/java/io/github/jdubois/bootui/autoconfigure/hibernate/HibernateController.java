package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Hibernate Advisor panel.
 *
 * <p>{@code GET} returns the last report (initially "not scanned"); {@code POST /scan} reads the
 * Hibernate/JPA metamodel and evaluates a bounded, static ruleset against mapped application
 * entities.</p>
 */
@RestController
@ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
@RequestMapping("/bootui/api/hibernate")
public class HibernateController {

    private final HibernateScanner scanner;

    private volatile HibernateReport lastReport;

    @Autowired
    public HibernateController(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        this(new HibernateScanner(entityManagerFactories, beanFactories, environment, Clock.systemUTC()));
    }

    HibernateController(HibernateScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public HibernateReport hibernate() {
        return lastReport;
    }

    @PostMapping("/scan")
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
