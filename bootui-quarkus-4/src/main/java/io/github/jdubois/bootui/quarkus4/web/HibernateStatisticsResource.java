package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the standalone Hibernate Statistics runtime-monitoring panel (Database group)
 * ({@code GET /bootui/api/hibernate-statistics}), separate from the static Hibernate (ORM mapping) advisor
 * served by {@link HibernateResource}.
 *
 * <p>A thin transport adapter over the shared engine {@link HibernateStatisticsService}, which reports the
 * panel unavailable (rather than faking data) when no {@code SessionFactory} is reachable or
 * {@code hibernate.generate_statistics} is disabled. {@code POST /enable} explicitly enables collection
 * for the current runtime without persisting configuration or resetting counters.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code HibernateStatisticsService} is
 * always wired (it holds no {@code jakarta.persistence} type): when {@code quarkus-hibernate-orm} is absent
 * the statistics service's provider is unsatisfied, so {@code GET} renders the panel unavailable — it never
 * fails. Availability of the <em>panel</em> in the manifest, by contrast, tracks the {@code HIBERNATE_ORM}
 * capability (see {@code QuarkusPanelAvailability}), exactly like the Hibernate advisor panel.</p>
 */
@ApplicationScoped
@Path("/bootui/api/hibernate-statistics")
public class HibernateStatisticsResource {

    private final HibernateStatisticsService statisticsService;

    @Inject
    public HibernateStatisticsResource(HibernateStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateStatisticsReport statistics() {
        return statisticsService.report();
    }

    @POST
    @Path("/enable")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateStatisticsReport enable() {
        return statisticsService.enable();
    }
}
