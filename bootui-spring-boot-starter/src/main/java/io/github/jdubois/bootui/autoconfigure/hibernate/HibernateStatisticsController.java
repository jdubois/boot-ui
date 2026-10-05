package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the standalone Hibernate Statistics runtime-monitoring panel (Database group), separate from the
 * static Hibernate Advisor panel served by {@link HibernateController}.
 *
 * <p>{@code GET} is a thin transport adapter over the shared engine {@link HibernateStatisticsService}, which
 * reads the host application's Hibernate {@code SessionFactory} statistics. It reports the panel unavailable
 * (rather than faking data) when no {@code SessionFactory} is reachable or {@code hibernate.generate_statistics}
 * is disabled. {@code POST /enable} explicitly enables collection for the current runtime; it does not
 * persist configuration or reset counters.</p>
 */
@RestController
@ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/hibernate-statistics")
public class HibernateStatisticsController {

    private final HibernateStatisticsService statisticsService;

    public HibernateStatisticsController(HibernateStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping
    public HibernateStatisticsReport statistics() {
        return statisticsService.report();
    }

    @PostMapping("/enable")
    public HibernateStatisticsReport enable() {
        return statisticsService.enable();
    }
}
