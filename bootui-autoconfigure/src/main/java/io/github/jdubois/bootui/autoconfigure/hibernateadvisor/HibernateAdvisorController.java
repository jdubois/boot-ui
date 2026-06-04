package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorReport;
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
@RequestMapping("/bootui/api/hibernate-advisor")
public class HibernateAdvisorController {

    private final HibernateAdvisorScanner scanner;

    private volatile HibernateAdvisorReport lastReport;

    @Autowired
    public HibernateAdvisorController(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        this(new HibernateAdvisorScanner(entityManagerFactories, beanFactories, environment, Clock.systemUTC()));
    }

    HibernateAdvisorController(HibernateAdvisorScanner scanner) {
        this.scanner = scanner;
        this.lastReport = scanner.initialReport();
    }

    @GetMapping
    public HibernateAdvisorReport hibernateAdvisor() {
        return lastReport;
    }

    @PostMapping("/scan")
    public HibernateAdvisorReport scan() {
        HibernateAdvisorReport report = scanner.scan();
        lastReport = report;
        return report;
    }
}
